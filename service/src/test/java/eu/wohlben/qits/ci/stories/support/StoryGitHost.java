package eu.wohlben.qits.ci.stories.support;

import eu.wohlben.qits.ci.githost.StubGitHost;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The <b>outgoing</b> tap for the git host: what the launched qits-ci read while it was deciding
 * whether a commit declares a pipeline, and while it was reading that pipeline back.
 *
 * <p>Nothing in this JVM is on that path — the consumer is a packaged process on the far side of a
 * socket — so the only place the traffic exists is the far side's own record of it. {@link
 * StubGitHost#requestLog()} is that record, appended to per answered request as {@code %m %U %s},
 * and this class turns it into {@link NetworkCapture} edges.
 *
 * <h2>Why a file, and why a floor</h2>
 *
 * <p>The stub is started by the test-resource lifecycle and read by a story method, and those need
 * not share a classloader — a static list written by one is not the list the other reads. A file is
 * a path both resolve identically, and it survives the surefire→failsafe boundary, which is exactly
 * why {@link #install()} takes a <b>floor</b>: every line already present belongs to an earlier
 * build or to the {@code @QuarkusTest} suites, and none of it is a story's.
 *
 * <p>The supplier is <b>cumulative and prefix-stable</b>, which is what the framework's per-source
 * cursor requires: it returns every edge harvested so far, in arrival order, and a line it decided
 * to skip is never in the list at all — so skipping can never shift an earlier story's slice, while
 * moving the floor would.
 *
 * <h2>Attribution</h2>
 *
 * <p>Every edge here is {@code qits-ci -> qits-githost}: the initiator is the service under test,
 * not the person who caused it, because direction is always <i>who dialled</i>. There is therefore
 * no actor to stamp and no hand-over to get wrong — which is the whole difference between this tap
 * and an inbound one.
 */
public final class StoryGitHost {

  /** How a diagram names the service this stub impersonates. */
  public static final String SERVICE_NAME = "qits-githost";

  /** One registration per JVM; re-registering under this id would keep the cursor anyway. */
  private static final String SOURCE_ID = "stub-git-host";

  /** How long {@link #awaitRead} waits for a line to reach disk. A ceiling, not a budget. */
  private static final Duration FLUSH_PATIENCE = Duration.ofSeconds(10);

  private static final long POLL_MILLIS = 25;

  private static final Object LOCK = new Object();

  private static boolean registered;

  /** How many lines the file already held when the first story class installed the tap. */
  private static int floor;

  /** How many lines have already been turned into edges — the harvest cursor. */
  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryGitHost() {}

  /**
   * Register the tap once per JVM, taking the current end of the recording as the floor. Called
   * from every story class's {@code @BeforeAll}; the first one to run is what bounds what any story
   * can see.
   */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = readLines().size();
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryGitHost::edges);
      registered = true;
    }
  }

  /**
   * Wait, briefly and without asserting anything, for a line containing {@code fragment}.
   *
   * <p>A read qits-ci makes on its own worker can still be in flight when the story's last
   * assertion passes, and a line that lands after the drain is a line in the <em>next</em> story's
   * diagram. Deliberately silent on timeout: the proof is the {@code assertEdge} in {@code
   * @AfterAll}, which names the missing edge, and a failure here would only obscure it.
   */
  public static void awaitRead(String fragment) {
    long deadline = System.nanoTime() + FLUSH_PATIENCE.toNanos();
    while (true) {
      for (String line : readLines()) {
        if (line.contains(fragment)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      sleep();
    }
  }

  /** The label an answered read renders as, once scrubbed — what an assertion has to spell. */
  public static String label(String method, String uri, int status) {
    return Labels.scrub(method + " " + uri + " -> " + status);
  }

  // --- the source --------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = readLines();
    if (harvested > lines.size()) {
      // The file was truncated under us (a `clean` mid-run). Start over rather than mis-slice.
      harvested = 0;
      floor = 0;
      lines = readLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /**
   * One recorded line as an edge, or nothing when it is a read no story can own.
   *
   * <p><b>The candidate listing is the one exclusion, and it is a stability decision rather than a
   * judgement about what matters.</b> {@code GET /git} is how qits-ci learns which repositories
   * exist, and {@code HttpGitHostRepoListing} caches a successful answer for five seconds — so that
   * one burst of events costs one listing rather than one per frame. Which story pays for the read
   * is therefore a stopwatch question: a story running six seconds after its neighbour reads it and
   * one running four seconds after does not, and an edge that comes and goes between runs moves the
   * story's {@code networkHash} with nothing having changed. What every story <em>does</em> carry is
   * the reads that follow it — the config directory at the branch and each declaring file at the
   * resolved sha — which are the evidence that ci went to the git host at all, per repository and
   * per run.
   */
  private static Optional<NetworkEdge> edge(String line) {
    // "%m %U %s" — three fields, no quoting, and a URI can carry no raw space.
    String[] fields = line.strip().split(" ");
    if (fields.length != 3 || !fields[1].startsWith("/")) {
      return Optional.empty();
    }
    if (fields[1].equals("/git") || fields[1].equals("/git/")) {
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE, SERVICE_NAME, fields[0] + " " + fields[1] + " -> " + fields[2]));
  }

  /** Everything recorded since the floor — i.e. everything a story could own. */
  private static List<String> readLines() {
    List<String> all = allLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the stub appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> allLines() {
    Path file = StubGitHost.requestLog();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
