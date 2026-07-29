package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The dedicated test the third risk of the implementation plan asks for.
 *
 * <p>The single-threaded run worker used to park on a process; it now parks on a socket. Same
 * blocking discipline, new failure mode: a future that never completes would wedge <b>all</b> of CI,
 * not just its own run. So every await in {@code daemonhost} carries its transition's timeout, and
 * this holds that two ways — behaviourally, that each of the three awaits returns by its deadline
 * with nothing on the other end; and structurally, that no second, untimed wait was added later.
 *
 * <p>Plain JUnit rather than {@code @QuarkusTest}: nothing here needs a socket, and it keeps the
 * test out of the port race the repo's known flake lives in.
 */
public class CiDaemonRegistryTimeoutTest {

  /**
   * Deliberately short. The assertion is not "60s means 60s", it is that a deadline is honoured at
   * all — a missing timeout does not fail slowly here, it never returns.
   */
  private static final Duration DEADLINE = Duration.ofMillis(150);

  /** Generous: the point is bounded, not fast. A hang fails this by timing the suite out. */
  private static final long MUST_RETURN_WITHIN_MS = 5_000;

  @Test
  public void everyAwaitReturnsWhenNothingEverDials() {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-silent", 0, null);

    long start = System.nanoTime();
    assertFalse(
        registry.awaitRegistered(credentials.daemonId(), DEADLINE),
        "a container that never dialled is the never-registered state, not a wait forever");
    assertEquals(
        CiDaemonRegistry.Initialization.Status.NEVER_INITIALIZED,
        registry.awaitInitialized(credentials.daemonId(), DEADLINE).status());
    assertEquals(
        CiDaemonRegistry.Completion.Status.NO_ANSWER,
        registry.awaitFinished(credentials.daemonId(), DEADLINE).status());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(elapsedMs >= DEADLINE.toMillis(), "the deadlines must actually be waited out");
    assertTrue(elapsedMs < MUST_RETURN_WITHIN_MS, "three deadlines took " + elapsedMs + "ms");
  }

  @Test
  public void awaitingALaunchThatIsNotOnTheBooksReturnsAtOnce() {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    // The restart story: after a reap (or a fresh process) there is no record, and a caller holding
    // a stale id must get an answer rather than a deadline's worth of silence.
    assertFalse(registry.awaitRegistered("gone", Duration.ofSeconds(30)));
    assertEquals(
        CiDaemonRegistry.Initialization.Status.CONNECTION_LOST,
        registry.awaitInitialized("gone", Duration.ofSeconds(30)).status());
    assertEquals(
        CiDaemonRegistry.Completion.Status.CONNECTION_LOST,
        registry.awaitFinished("gone", Duration.ofSeconds(30)).status());
  }

  @Test
  public void reapingResolvesWhateverTheWorkerIsParkedOn() throws Exception {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-reaped", 0, null);

    Thread reaper =
        new Thread(
            () -> {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              registry.reap(credentials.daemonId());
            });
    reaper.start();

    long start = System.nanoTime();
    CiDaemonRegistry.Completion completion =
        registry.awaitFinished(credentials.daemonId(), Duration.ofSeconds(30));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    reaper.join();

    assertEquals(CiDaemonRegistry.Completion.Status.CONNECTION_LOST, completion.status());
    assertTrue(elapsedMs < MUST_RETURN_WITHIN_MS, "a reap must not leave the worker on its deadline");
  }

  @Test
  public void secretsAreFreshPerLaunchAndLongEnoughToBeWorthComparing() {
    CiDaemonRegistry registry = new CiDaemonRegistry();
    CiDaemonRegistry.Credentials first = registry.registerLaunch("run-a", 0, null);
    CiDaemonRegistry.Credentials second = registry.registerLaunch("run-a", 1, null);

    assertNotEquals(first.daemonId(), second.daemonId());
    assertNotEquals(first.secret(), second.secret());
    // 32 bytes of SecureRandom, base64url without padding.
    assertEquals(43, first.secret().length(), first.secret());
    assertTrue(first.secret().matches("[A-Za-z0-9_-]+"), first.secret());
    assertEquals(2, registry.size());
  }

  /**
   * The structural half: grep the package for a wait with no deadline on it. A behavioural test can
   * only prove the awaits that exist today are bounded — this is what stops a fourth one from being
   * added untimed next year, and it is cheap enough to be worth the unusual shape.
   *
   * <p>{@code sendTextAndAwait} is on the list beside {@code get()} and {@code join()} because it is
   * exactly {@code sendText(m).await().indefinitely()} — the precedent's spelling, and an untimed
   * block on a socket whose peer runs repo-controlled code.
   */
  @Test
  public void noAwaitInTheDaemonhostPackageIsUntimed() throws IOException {
    Path pkg = Path.of("src/main/java/eu/wohlben/qits/ci/daemonhost");
    assertTrue(Files.isDirectory(pkg), "expected the daemonhost sources at " + pkg.toAbsolutePath());

    Pattern untimed =
        Pattern.compile("\\.(get|join|indefinitely)\\(\\)|sendTextAndAwait|sendBinaryAndAwait");
    List<String> offences = new ArrayList<>();
    try (Stream<Path> sources = Files.list(pkg)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
          Matcher matcher = untimed.matcher(stripComment(lines.get(i)));
          if (matcher.find()) {
            offences.add(source.getFileName() + ":" + (i + 1) + " " + lines.get(i).strip());
          }
        }
      }
    }
    assertEquals(List.of(), offences, "untimed waits in daemonhost");
  }

  /** Crude but sufficient: the pattern only has to survive prose about itself in the javadoc. */
  private static String stripComment(String line) {
    String trimmed = line.strip();
    if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
      return "";
    }
    return line;
  }
}
