package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Heartbeat;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The control socket and the registry behind it, driven by a real WebSocket from a scripted {@link
 * FakeCiDaemon}. No docker and no daemon binary: the host cannot tell this client from a container,
 * so everything about admission, framing, dispatch and the blocking bridge is provable here and only
 * the round trip through a real image is left to {@code CiDaemonHandshakeIT}.
 *
 * <p>Addresses the socket through {@code @TestHTTPResource} rather than a hard-coded port, and
 * through its <b>absolute</b> path — {@code /ci/daemon} is a literal that does not follow {@code
 * quarkus.rest.path}, and it is the string {@code CiDaemonLauncher} injects into every container, so
 * a test that addressed it relatively would not catch a segment regression.
 */
@QuarkusTest
public class CiDaemonSocketTest {

  private static final Duration SOON = Duration.ofSeconds(10);

  @Inject CiDaemonRegistry registry;

  @TestHTTPResource("/ci/daemon")
  URI endpoint;

  @Test
  public void anUnknownDaemonIdIsClosedWithAPolicyViolation() throws Exception {
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, "no-such-daemon", "whatever")) {
      assertEquals(
          (Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED,
          daemon.awaitClose(SOON),
          "a dial the registry has no launch record for must be refused");
    }
  }

  @Test
  public void aDialWithNoHeadersAtAllIsClosedWithAPolicyViolation() throws Exception {
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(endpoint, null, null)) {
      assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
    }
  }

  @Test
  public void aWrongSecretIsClosedWithAPolicyViolation() throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-wrong", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret() + "x")) {
      assertEquals((Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED, daemon.awaitClose(SOON));
      // Refused, so the launch never reached CONNECTED and the worker's await must still time out.
      assertFalse(registry.awaitRegistered(credentials.daemonId(), Duration.ofMillis(200)));
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void aSecondDialForAnAlreadyConnectedDaemonIsClosed() throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-redial", 0, null);
    try (FakeCiDaemon first =
            FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret());
        FakeCiDaemon second =
            FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret())) {
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));
      assertEquals(
          (Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED,
          second.awaitClose(SOON),
          "a re-dial for a connected launch is a claim on it, not a reconnect");
      assertTrue(first.isOpen(), "the connection that got there first keeps the launch");
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void aRegisteredDaemonIsAckedWithTheHostsCapabilityVersion() throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-ack", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret())) {
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));
      assertEquals(CiDaemonRegistry.Phase.CONNECTED, registry.phaseOf(credentials.daemonId()));

      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      Ack ack = assertInstanceOf(Ack.class, daemon.next(SOON));
      // The version must travel back: a daemon reading no version reads 0, mismatches, and exits.
      assertEquals(CiDaemonProtocol.CAPABILITY_VERSION, ack.capabilityVersion());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void aHelloClaimingAnotherDaemonIsClosedRatherThanBelieved() throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-claim", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret())) {
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));
      daemon.send(new Hello("somebody-elses-daemon", CiDaemonProtocol.CAPABILITY_VERSION));
      assertEquals(
          (Short) (short) CiDaemonRegistry.CLOSE_UNAUTHORIZED,
          daemon.awaitClose(SOON),
          "the daemonId on the wire is a claim the host checks, not an identity it accepts");
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void anUndecodableFrameCostsTheFrameAndNotTheConnection() throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-garbage", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret())) {
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));

      // Each of these throws inside the shared codec: an unknown type, a missing type, an unknown
      // InitFailed reason, and an absent stream on a chunk. The strictness is deliberate there and
      // must be caught here, or one malformed frame from a container takes the socket with it.
      daemon.sendRaw("{\"type\":\"somethingElse\"}");
      daemon.sendRaw("{\"nope\":1}");
      daemon.sendRaw("{\"type\":\"initFailed\",\"reason\":\"COSMIC_RAYS\"}");
      daemon.sendRaw("{\"type\":\"stepChunk\",\"correlationId\":\"c\",\"seq\":0,\"text\":\"x\"}");
      daemon.sendRaw("not json at all");

      // Still speaking: the connection survived all five.
      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));
      assertTrue(daemon.isOpen());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void theWholeStepLifecycleRunsThroughTheBlockingBridge() throws Exception {
    List<String> chunks = Collections.synchronizedList(new ArrayList<>());
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch(
            "run-lifecycle", 3, (stream, seq, text) -> chunks.add(stream + ":" + seq + ":" + text));
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret())) {
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));

      daemon.send(new Hello(credentials.daemonId(), CiDaemonProtocol.CAPABILITY_VERSION));
      assertInstanceOf(Ack.class, daemon.next(SOON));
      daemon.send(new Heartbeat());
      daemon.send(new Initialized());

      CiDaemonRegistry.Initialization initialization =
          registry.awaitInitialized(credentials.daemonId(), SOON);
      assertEquals(
          CiDaemonRegistry.Initialization.Status.INITIALIZED, initialization.status());
      assertEquals(CiDaemonRegistry.Phase.INITIALIZED, registry.phaseOf(credentials.daemonId()));

      // The step arrives as the answer to Initialized — the host initiates nothing.
      String correlationId = registry.sendRunStep(credentials.daemonId(), "echo hi", 60);
      RunStep runStep = assertInstanceOf(RunStep.class, daemon.next(SOON));
      assertEquals(correlationId, runStep.correlationId());
      assertEquals("echo hi", runStep.script());
      assertEquals(60, runStep.timeoutSeconds());

      daemon.send(new StepChunk(correlationId, 0, Stream.OUT, "hi\n"));
      daemon.send(new StepChunk(correlationId, 1, Stream.ERR, "warn\n"));
      daemon.send(new StepFinished(correlationId, 0, false));

      CiDaemonRegistry.Completion completion =
          registry.awaitFinished(credentials.daemonId(), SOON);
      assertEquals(CiDaemonRegistry.Completion.Status.FINISHED, completion.status());
      assertEquals(0, completion.exitCode());
      assertFalse(completion.timedOut());
      assertEquals(List.of("OUT:0:hi\n", "ERR:1:warn\n"), chunks);
    } finally {
      registry.reap(credentials.daemonId());
    }
    assertNull(registry.phaseOf(credentials.daemonId()), "a reaped launch leaves no record");
  }


  @Test
  public void aStructuredSetupFailureReachesTheAwaitWithItsReason() throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-shagone", 0, null);
    try (FakeCiDaemon daemon =
        FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret())) {
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));
      daemon.send(new InitFailed(InitFailed.Reason.SHA_GONE, "fatal: reference is not a tree"));

      CiDaemonRegistry.Initialization initialization =
          registry.awaitInitialized(credentials.daemonId(), SOON);
      assertEquals(CiDaemonRegistry.Initialization.Status.INIT_FAILED, initialization.status());
      // SHA_GONE is what carries the force-push semantic the orchestrator acts on; it must not
      // arrive as a generic failure with a suspicious exit code.
      assertEquals(InitFailed.Reason.SHA_GONE, initialization.reason());
      assertNotNull(initialization.detail());
    } finally {
      registry.reap(credentials.daemonId());
    }
  }

  @Test
  public void aSocketLostMidStepCompletesTheAwaitAsConnectionLostRatherThanTimingOut()
      throws Exception {
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch("run-drop", 0, null);
    try {
      FakeCiDaemon daemon =
          FakeCiDaemon.dial(endpoint, credentials.daemonId(), credentials.secret());
      assertTrue(registry.awaitRegistered(credentials.daemonId(), SOON));
      daemon.send(new Initialized());
      assertEquals(
          CiDaemonRegistry.Initialization.Status.INITIALIZED,
          registry.awaitInitialized(credentials.daemonId(), SOON).status());
      registry.sendRunStep(credentials.daemonId(), "sleep 600", 600);
      assertInstanceOf(RunStep.class, daemon.next(SOON));

      daemon.close();

      // A generous deadline that must NOT be spent: the close resolves the await immediately, which
      // is the difference between a distinguishable outcome and a run that looks merely slow.
      long start = System.nanoTime();
      CiDaemonRegistry.Completion completion =
          registry.awaitFinished(credentials.daemonId(), Duration.ofSeconds(30));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertEquals(CiDaemonRegistry.Completion.Status.CONNECTION_LOST, completion.status());
      assertTrue(elapsedMs < 10_000, "the lost socket must resolve the await, not expire it");
    } finally {
      registry.reap(credentials.daemonId());
    }
  }
}
