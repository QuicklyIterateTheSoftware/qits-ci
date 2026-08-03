package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.control.DaemonProbe.ProbeResult;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.AckReceived;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link CiDaemonContainerProbe}'s verdict logic, driven by a real WebSocket from {@link
 * FakeCiDaemon} against the real control socket -- no docker anywhere in this class, which is what
 * lets it run in every JVM suite. Drives {@link CiDaemonContainerProbe#awaitVerdict} directly,
 * package-private on purpose: {@link CiDaemonContainerProbe#probe} is skipped under {@code
 * LaunchMode.TEST} (this file's own launch mode), so a test that wants the verdict logic under a
 * real dial has to reach past that guard -- the same way {@code CiDaemonHandshakeIT} reaches past
 * {@code CiDaemonLauncher.onStart} by hand-wiring its own instance. The real container path --
 * {@link CiDaemonContainerProbe#probeUnconditionally} -- is {@code CiDaemonContainerProbeIT}'s,
 * tagged {@code extended}.
 *
 * <p>A round trip needs both halves proven, so most of these scripts now run one frame longer than
 * they used to: {@code Hello} gets an {@code Ack} as before, and a candidate that means to be
 * {@link Verdict#PROVEN} answers it with {@link AckReceived} -- the confirmation that the host's
 * own frame actually arrived, which {@code Hello} alone never demonstrated.
 */
@QuarkusTest
public class CiDaemonContainerProbeTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  @Inject CiDaemonContainerProbe probe;

  @Inject CiDaemonRegistry registry;

  @TestHTTPResource("/ci/daemon")
  URI controlSocket;

  /**
   * A registration that never arrives would otherwise cost this class the full {@code
   * qits.ci.daemon-register-timeout-seconds} (60s by default) per test -- staged short through
   * {@link ClientProxy#unwrap}, the same trick {@code CiDaemonPinTest} uses on {@code
   * CiDaemonPins.configuredVersion}, and restored after.
   */
  @AfterEach
  void restoreTheShippedTimeout() {
    ClientProxy.unwrap(probe).registerTimeoutSeconds = 60;
  }

  @Test
  public void aDaemonThatNeverDialsIsRejectedWithLogsRecorded() throws Exception {
    ClientProxy.unwrap(probe).registerTimeoutSeconds = 1;

    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("probe-never-dials", 0, null);
    try {
      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-never-dials", 0));
      assertEquals(Verdict.REJECTED, result.verdict());
      // No real container exists in this test, so "docker logs" answers docker's own account of
      // that (absent docker, or an unknown container) rather than a bootstrap's stderr -- either
      // way it is captured, which is the property under test.
      assertNotNull(result.detail());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void aDaemonAnnouncingTheWrongCapabilityVersionIsRejected() throws Exception {
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-wrong-capability", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);
      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION + 1));
      // Still Acked -- a mismatch is logged and left to the daemon to act on, never refused here.
      assertInstanceOf(Ack.class, daemon.next(SOON));

      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-wrong-capability", 0));
      assertEquals(Verdict.REJECTED, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void aDaemonAnnouncingTheHostsOwnCapabilityVersionIsProven() throws Exception {
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-matching-capability", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);
      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));
      daemon.send(new AckReceived());

      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-matching-capability", 0));
      assertEquals(Verdict.PROVEN, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  /**
   * The regression this whole change exists to fix: {@code Hello} alone used to be enough for
   * {@link Verdict#PROVEN}, which proved only daemon→host. A daemon that never confirms the Ack
   * looked identical to a working one right up to the moment a real run needed to send it a {@code
   * RunStep} and nothing happened.
   */
  @Test
  public void aDaemonThatSaysHelloButNeverConfirmsTheAckIsRejectedAtTheDeadline() throws Exception {
    ClientProxy.unwrap(probe).registerTimeoutSeconds = 1;

    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-no-ack-confirmation", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);
      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));
      // Unlike a real ci-daemon, this fake reads the Ack and says nothing back.

      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(),
              CiDaemonLauncher.containerName("probe-no-ack-confirmation", 0));
      assertEquals(Verdict.REJECTED, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  /**
   * The other way the Ack can go unconfirmed: the daemon is gone before it can act on it at all,
   * whether or not the host's send actually reached the wire. Distinguishable from the never-says-
   * hello case only by what got that far -- the verdict is the same {@link Verdict#REJECTED}.
   */
  @Test
  public void aDaemonThatDisconnectsRightAfterHelloIsRejectedBecauseTheAckIsNeverConfirmed()
      throws Exception {
    ClientProxy.unwrap(probe).registerTimeoutSeconds = 1;

    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-ack-undelivered", 0, null);
    try {
      try (FakeCiDaemon daemon =
          FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
        registry.awaitRegistered(credentials.daemonId(), SOON);
        daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
        // Closed here, on purpose: this daemon will never read whatever the host sent back, Ack
        // included, so it can never confirm it either.
      }

      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-ack-undelivered", 0));
      assertEquals(Verdict.REJECTED, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  /**
   * The cosmetic regression this feature also fixes: verdict used to settle at {@code Hello}, so a
   * successful probe's reap raced the host's own {@code Ack} write and logged {@code Could not send
   * Ack ... WebSocket is closed} on a probe that had, in fact, succeeded. Waiting for {@link
   * AckReceived} means the reap can no longer happen before that send is not just attempted but
   * answered, so this asserts the WARN is gone rather than trusting the reordering above to imply
   * it.
   */
  @Test
  public void aSuccessfulProbeLogsNoFailedAckSend() throws Exception {
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-clean-ack-send", 0, null);
    java.util.logging.Logger registryLog =
        java.util.logging.Logger.getLogger(CiDaemonRegistry.class.getName());
    List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
              warnings.add(record.getMessage());
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    registryLog.addHandler(capture);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);
      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));
      daemon.send(new AckReceived());

      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-clean-ack-send", 0));
      assertEquals(Verdict.PROVEN, result.verdict());
    } finally {
      registryLog.removeHandler(capture);
      registry.reap(credentials.daemonId());
    }
    List<String> failedSends = warnings.stream().filter(m -> m != null && m.contains("Could not send")).toList();
    assertEquals(List.of(), failedSends, "a successful probe must not race its own Ack send: " + warnings);
  }

  /**
   * The regression: registration completes at websocket admission, one round trip before a real
   * daemon has said anything at all. The bug read the capability version right there and rejected
   * every genuine daemon (production log: "announced capability null"). Every other test here dials
   * and says Hello back-to-back, so {@code awaitVerdict} never actually observed the gap between the
   * two -- this test forces it: the verdict is started the moment registration is confirmed, proven
   * still pending across a real sleep (which is what the bug would not have been -- it would already
   * have answered {@code REJECTED}), and only then given its Hello.
   */
  @Test
  public void aDaemonThatDelaysHelloPastRegistrationIsStillProvenOnceItArrives() throws Exception {
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-delayed-hello", 0, null);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);

      Future<ProbeResult> verdict =
          executor.submit(
              () ->
                  probe.awaitVerdict(
                      credentials.daemonId(), CiDaemonLauncher.containerName("probe-delayed-hello", 0)));

      // The gap the bug fell into: registered, and nothing said yet. A sleep this long is well past
      // the moment the old code would have already answered REJECTED off a stale capability read.
      Thread.sleep(500);
      assertFalse(verdict.isDone(), "awaitVerdict must still be waiting for Hello, not the admission");

      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));
      daemon.send(new AckReceived());

      ProbeResult result = verdict.get(10, TimeUnit.SECONDS);
      assertEquals(Verdict.PROVEN, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
      executor.shutdownNow();
    }
  }

  /**
   * The same regression-proof as {@link #aDaemonThatDelaysHelloPastRegistrationIsStillProvenOnceItArrives},
   * one stage later: a verdict that settled at {@code Hello} would already have answered {@code
   * PROVEN} here, before the daemon ever confirmed the host's {@code Ack} arrived.
   */
  @Test
  public void aDaemonThatDelaysItsAckConfirmationIsStillProvenOnceItArrives() throws Exception {
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-delayed-confirmation", 0, null);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);
      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));

      Future<ProbeResult> verdict =
          executor.submit(
              () ->
                  probe.awaitVerdict(
                      credentials.daemonId(),
                      CiDaemonLauncher.containerName("probe-delayed-confirmation", 0)));

      // Hello answered, Ack read, and still nothing confirmed -- the gap the old Hello-only verdict
      // could not see at all.
      Thread.sleep(500);
      assertFalse(
          verdict.isDone(), "awaitVerdict must still be waiting for AckReceived, not the Ack read");

      daemon.send(new AckReceived());

      ProbeResult result = verdict.get(10, TimeUnit.SECONDS);
      assertEquals(Verdict.PROVEN, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
      executor.shutdownNow();
    }
  }

  @Test
  public void aDaemonThatRegistersButNeverSaysHelloIsRejectedAtTheDeadline() throws Exception {
    ClientProxy.unwrap(probe).registerTimeoutSeconds = 1;

    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch("probe-registers-no-hello", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(controlSocket, credentials.daemonId(), credentials.secret())) {
      registry.awaitRegistered(credentials.daemonId(), SOON);

      // Registered, but this daemon -- unlike a real one -- never says Hello. A real daemon says it
      // immediately, so registered-but-silent for the whole deadline is a genuine REJECTED.
      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-registers-no-hello", 0));
      assertEquals(Verdict.REJECTED, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }
}
