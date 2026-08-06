package eu.wohlben.qits.ci.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.smallrye.mutiny.Uni;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wire half of the deploy announcement: what actually leaves the process — method, payload,
 * credential — against a local server standing in for qits-platform-deployments. Plain JUnit over a
 * directly-constructed notifier: the seam's <em>semantics</em> (green announces, red does not) are
 * held in the ci module's {@code PdNotifySeamTest}; this test pins the contract the deployer's
 * intake parses, absolute path included, because a mismatch there is silent on both sides.
 *
 * <p>Delivery is fire-and-forget, so assertions wait on a queue the fixture fills rather than on
 * the call returning.
 */
class PdBuildNotifierTest {

  private record Received(String path, String authorization, Map<String, Object> body) {}

  private HttpServer server;
  private BlockingQueue<Received> received;

  @BeforeEach
  void startServer() throws IOException {
    received = new ArrayBlockingQueue<>(4);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(body, Map.class);
            received.add(
                new Received(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    parsed));
          } finally {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private PdBuildNotifier notifier() {
    PdBuildNotifier notifier = new PdBuildNotifier();
    notifier.intakeUrl =
        "http://127.0.0.1:" + server.getAddress().getPort() + "/platform-deployments/api/events/build-succeeded";
    notifier.objectMapper = new ObjectMapper();
    return notifier;
  }

  private Received await() throws InterruptedException {
    Received first = received.poll(10, TimeUnit.SECONDS);
    if (first == null) {
      fail("no deploy announcement arrived within the deadline");
    }
    return first;
  }

  @Test
  void thePayloadCarriesTheRunsCoordinatesAndNoTokenByDefault() throws Exception {
    notifier().onRunSucceeded("run-1", "repo-1", "epic/some-epic", "a".repeat(40));

    Received event = await();
    assertEquals("/platform-deployments/api/events/build-succeeded", event.path());
    assertEquals(
        Map.of(
            "runId", "run-1",
            "repoId", "repo-1",
            "branch", "epic/some-epic",
            "commitSha", "a".repeat(40)),
        event.body());
    // The shipped posture: no client credentials configured, so the call is exactly what it was
    // before qits-idp existed. This pins that a credential does not quietly grow onto the wire.
    assertNull(event.authorization());
  }

  @Test
  void aConfiguredCredentialTravelsAsABearer() throws Exception {
    PdBuildNotifier notifier = notifier();
    notifier.bearer = bearerOf("minted-by-qits-idp");

    notifier.onRunSucceeded("run-2", "repo-2", "main", "b".repeat(40));

    assertEquals("Bearer minted-by-qits-idp", await().authorization());
  }

  @Test
  void aCredentialThatCannotBeFetchedSkipsTheNotification() {
    PdBuildNotifier notifier = notifier();
    notifier.bearer =
        new PdBearer(true, null) {
          @Override
          public Uni<Optional<String>> bearer() {
            return Uni.createFrom().failure(new IllegalStateException("qits-idp is down"));
          }
        };

    // Skipped, not sent bare: a guarded intake would refuse an unauthenticated POST anyway, and
    // sending one would turn a credential problem into a mystery in another service's log.
    notifier.onRunSucceeded("run-3", "repo-3", "main", "c".repeat(40));
    assertNull(received.poll());
  }

  /** A stand-in for the real thing — what PdBearer answers once a deployment configures a client. */
  private static PdBearer bearerOf(String accessToken) {
    return new PdBearer(true, null) {
      @Override
      public Uni<Optional<String>> bearer() {
        return Uni.createFrom().item(Optional.of("Bearer " + accessToken));
      }
    };
  }

  @Test
  void anUnreachableIntakeNeitherBlocksNorThrows() {
    PdBuildNotifier notifier = new PdBuildNotifier();
    // A TEST-NET address nothing answers on: the 2s connect timeout belongs to the async send, so
    // the call itself has to return immediately — it runs on the single-threaded run worker.
    notifier.intakeUrl = "http://192.0.2.1:9/platform-deployments/api/events/build-succeeded";
    notifier.objectMapper = new ObjectMapper();

    long before = System.nanoTime();
    notifier.onRunSucceeded("run-4", "repo-4", "main", "d".repeat(40));
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
    assertTrue(
        elapsedMillis < 1_000,
        "fire-and-forget must not park the run worker (" + elapsedMillis + "ms)");
  }
}
