package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.control.DaemonProbe.ProbeResult;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
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

      ProbeResult result =
          probe.awaitVerdict(
              credentials.daemonId(), CiDaemonLauncher.containerName("probe-matching-capability", 0));
      assertEquals(Verdict.PROVEN, result.verdict());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }
}
