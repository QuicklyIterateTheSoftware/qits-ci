package eu.wohlben.qits.ci.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wire half of the CD announcement: what actually leaves the process — method, payload, token
 * header — against a local server standing in for qits-cd. Plain JUnit over a directly-constructed
 * notifier: the seam's <em>semantics</em> (green announces, red does not) are held in the ci
 * module's {@code CdNotifySeamTest}; this test pins the contract cd's intake parses.
 *
 * <p>Delivery is fire-and-forget, so assertions wait on a queue the fixture fills rather than on
 * the call returning.
 */
class CdBuildNotifierTest {

  private record Received(String path, String token, Map<String, Object> body) {}

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
                    exchange.getRequestHeaders().getFirst("X-CD-Token"),
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

  private CdBuildNotifier notifier(Optional<String> token) {
    CdBuildNotifier notifier = new CdBuildNotifier();
    notifier.intakeUrl =
        "http://127.0.0.1:" + server.getAddress().getPort() + "/cd/api/events/build-succeeded";
    notifier.token = token;
    notifier.objectMapper = new ObjectMapper();
    return notifier;
  }

  private Received await() throws InterruptedException {
    Received first = received.poll(10, TimeUnit.SECONDS);
    if (first == null) {
      fail("no CD notification arrived within the deadline");
    }
    return first;
  }

  @Test
  void thePayloadCarriesTheRunsCoordinates() throws Exception {
    notifier(Optional.empty()).onRunSucceeded("run-1", "repo-1", "epic/some-epic", "a".repeat(40));

    Received event = await();
    assertEquals("/cd/api/events/build-succeeded", event.path());
    assertEquals(
        Map.of(
            "runId", "run-1",
            "repoId", "repo-1",
            "branch", "epic/some-epic",
            "commitSha", "a".repeat(40)),
        event.body());
  }

  @Test
  void aBlankTokenSendsNoHeaderAndAConfiguredOneTravels() throws Exception {
    notifier(Optional.of("   ")).onRunSucceeded("run-2", "repo-2", "main", "b".repeat(40));
    assertNull(await().token(), "a blank token must send no header (the guard's open mode)");

    notifier(Optional.of("cd-secret")).onRunSucceeded("run-3", "repo-3", "main", "c".repeat(40));
    assertEquals("cd-secret", await().token());
  }

  @Test
  void anUnreachableIntakeNeitherBlocksNorThrows() {
    CdBuildNotifier notifier = new CdBuildNotifier();
    // A TEST-NET address nothing answers on: the 2s connect timeout belongs to the async send, so
    // the call itself has to return immediately — it runs on the single-threaded run worker.
    notifier.intakeUrl = "http://192.0.2.1:9/cd/api/events/build-succeeded";
    notifier.token = Optional.empty();
    notifier.objectMapper = new ObjectMapper();

    long before = System.nanoTime();
    notifier.onRunSucceeded("run-4", "repo-4", "main", "d".repeat(40));
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
    assertTrue(
        elapsedMillis < 1_000,
        "fire-and-forget must not park the run worker (" + elapsedMillis + "ms)");
  }
}
