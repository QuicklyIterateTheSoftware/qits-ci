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

  /**
   * Every spelling of "wait with no deadline" this package must not contain. Hoisted out of the grep
   * below so it can be tested against known strings — a guard whose coverage is itself unasserted is
   * how {@code closeAndAwait} slipped past an earlier version of it.
   */
  private static final Pattern UNTIMED =
      Pattern.compile("\\.(get|join|indefinitely)\\(\\)|[A-Za-z]+AndAwait\\s*\\(");

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
   * <p><b>The {@code …AndAwait} family is banned by shape rather than by name</b>, and that
   * generalisation was bought the hard way. The pattern first listed {@code sendTextAndAwait} and
   * {@code sendBinaryAndAwait} explicitly, on the correct reasoning that each is
   * {@code sendText(m).await().indefinitely()} — and then sat green over two live calls to {@code
   * closeAndAwait}, which is the identical shape under a name nobody had thought to enumerate. The
   * untimed part of these lives inside the framework's default method, so it never appears in this
   * package's own source and only the <em>call</em> is visible here. Any convenience the extension
   * spells that way is one this package must not take: give the bounded form instead —
   * {@code close(…).await().atMost(…)}, {@code sendText(m).await().atMost(…)}.
   *
   * <p>The one thing the shape rule cannot catch is a wait added through some future spelling that
   * looks like neither. That is what the class javadoc on {@code CiDaemonRegistry} is for, and why
   * this test's failure message says what to do rather than only that something is wrong.
   */
  @Test
  public void noAwaitInTheDaemonhostPackageIsUntimed() throws IOException {
    Path pkg = Path.of("src/main/java/eu/wohlben/qits/ci/daemonhost");
    assertTrue(Files.isDirectory(pkg), "expected the daemonhost sources at " + pkg.toAbsolutePath());

    List<String> offences = new ArrayList<>();
    try (Stream<Path> sources = Files.list(pkg)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        List<String> lines = Files.readAllLines(source);
        for (int i = 0; i < lines.size(); i++) {
          Matcher matcher = UNTIMED.matcher(stripComment(lines.get(i)));
          if (matcher.find()) {
            offences.add(source.getFileName() + ":" + (i + 1) + " " + lines.get(i).strip());
          }
        }
      }
    }
    assertEquals(
        List.of(),
        offences,
        "untimed waits in daemonhost — the run worker parks here, so one of these wedges all of CI."
            + " Spell the bounded form instead: close(…).await().atMost(CLOSE_TIMEOUT),"
            + " sendText(m).await().atMost(SEND_TIMEOUT), future.get(timeout, unit)");
  }

  /**
   * The grep above can only fail on what its pattern matches, so assert what its pattern matches.
   *
   * <p>This exists because the earlier pattern enumerated {@code sendTextAndAwait} and {@code
   * sendBinaryAndAwait} by name and therefore stayed green over two live {@code closeAndAwait}
   * calls — the same {@code …().await().indefinitely()} under a name the list had not anticipated.
   * A guard that can be silently incomplete is worth what its coverage is, and its coverage was
   * never asserted. Now it is, in both directions: the conveniences must be caught, and the bounded
   * spellings the package actually uses must not be, or the guard becomes noise someone disables.
   */
  @Test
  public void theUntimedPatternCatchesEveryConvenienceAndNoBoundedSpelling() {
    for (String banned :
        List.of(
            "connection.closeAndAwait();",
            "connection.closeAndAwait(new CloseReason(1008, reason));",
            "connection.sendTextAndAwait(text);",
            "connection.sendBinaryAndAwait(bytes);",
            "connection.sendPingAndAwait(buffer);",
            "return future.get();",
            "return future.join();",
            "uni.await().indefinitely();")) {
      assertTrue(UNTIMED.matcher(banned).find(), "must be flagged: " + banned);
    }
    for (String allowed :
        List.of(
            "connection.close().await().atMost(CLOSE_TIMEOUT);",
            "connection.close(reason).await().atMost(CLOSE_TIMEOUT);",
            "connection.sendText(codec.encode(message)).await().atMost(SEND_TIMEOUT);",
            "return future.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);",
            "Launch launch = launches.get(daemonId);",
            "return relay.snapshot(runId).map(Snapshot::output).orElse(\"\");")) {
      assertFalse(UNTIMED.matcher(allowed).find(), "must not be flagged: " + allowed);
    }
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
