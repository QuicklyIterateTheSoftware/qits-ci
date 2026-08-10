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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>Delivery is asynchronous, so assertions wait on a queue the fixture fills rather than on the
 * call returning.
 *
 * <p><b>The retry cases are the measured loss.</b> A transient refusal right after an
 * qits-platform-idp cutover used to lose a green run's announcement outright, so the fixture can
 * refuse a chosen number of attempts and hand out a different token per attempt. The delays are the
 * notifier's own field ({@code retryDelays}, production ships 5s/15s/45s/2m) and every case here
 * sets milliseconds, which is the whole reason that field is not a constant.
 */
class PdBuildNotifierTest {

  private record Received(String path, String authorization, Map<String, Object> body) {}

  private static final List<Duration> FAST_RETRIES =
      List.of(Duration.ofMillis(10), Duration.ofMillis(10), Duration.ofMillis(10));

  private HttpServer server;
  private BlockingQueue<Received> received;

  /** Attempts the intake refuses before it starts accepting — the transient outage under test. */
  private final AtomicInteger refusalsLeft = new AtomicInteger();

  /** Every attempt, accepted or refused, so "exactly once" can be told from "delivered once". */
  private final AtomicInteger attempts = new AtomicInteger();

  /** Closed after each test, so a case's pending retries never leak into the next one. */
  private final List<PdBuildNotifier> notifiers = new ArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    received = new ArrayBlockingQueue<>(8);
    refusalsLeft.set(0);
    attempts.set(0);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          attempts.incrementAndGet();
          if (refusalsLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            // 503 rather than a dropped connection: a refusal is what the live platform measured,
            // and it must count as "not delivered" exactly as a connect failure does.
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
          }
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
    notifiers.forEach(PdBuildNotifier::stopRetrying);
    server.stop(0);
  }

  private PdBuildNotifier notifier() {
    PdBuildNotifier notifier = new PdBuildNotifier();
    notifier.intakeUrl =
        "http://127.0.0.1:" + server.getAddress().getPort() + "/platform-deployments/api/events/build-succeeded";
    notifier.objectMapper = new ObjectMapper();
    notifiers.add(notifier);
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
  void aRefusedDeliveryIsRetriedAndArrivesExactlyOnce() throws Exception {
    PdBuildNotifier notifier = notifier();
    notifier.retryDelays = FAST_RETRIES;
    refusalsLeft.set(2);

    notifier.onRunSucceeded("run-5", "repo-5", "main", "e".repeat(40));

    // The whole point of the change: the run the outage refused still reaches the deployer.
    assertEquals("run-5", await().body().get("runId"));
    assertEquals(3, attempts.get(), "two refusals then one acceptance");
    // And it stops there — a delivered announcement must not keep announcing itself.
    assertNull(received.poll(200, TimeUnit.MILLISECONDS));
  }

  @Test
  void everyAttemptFetchesTheCredentialAgain() throws Exception {
    PdBuildNotifier notifier = notifier();
    notifier.retryDelays = FAST_RETRIES;
    // What an idp cutover looks like from here: the token the first attempt presented is refused,
    // and the second attempt must present what the client holds NOW, not what it held then.
    AtomicInteger minted = new AtomicInteger();
    notifier.bearer =
        new PdBearer(true, null) {
          @Override
          public Uni<Optional<String>> bearer() {
            return Uni.createFrom().item(Optional.of("Bearer token-" + minted.incrementAndGet()));
          }
        };
    refusalsLeft.set(1);

    notifier.onRunSucceeded("run-6", "repo-6", "main", "f".repeat(40));

    assertEquals("Bearer token-2", await().authorization());
  }

  @Test
  void aRefusalThatNeverClearsGivesUpAfterTheWholeSchedule() throws Exception {
    PdBuildNotifier notifier = notifier();
    notifier.retryDelays = FAST_RETRIES;
    refusalsLeft.set(Integer.MAX_VALUE);

    notifier.onRunSucceeded("run-7", "repo-7", "main", "g".repeat(40));

    // Bounded, not endless: one attempt plus one per delay, then a WARN and nothing more.
    Thread.sleep(500);
    assertEquals(FAST_RETRIES.size() + 1, attempts.get());
    assertNull(received.poll());
  }

  @Test
  void aCredentialThatCannotBeFetchedNeverSendsTheRequestBare() throws Exception {
    PdBuildNotifier notifier = notifier();
    notifier.retryDelays = FAST_RETRIES;
    notifier.bearer =
        new PdBearer(true, null) {
          @Override
          public Uni<Optional<String>> bearer() {
            return Uni.createFrom().failure(new IllegalStateException("qits-idp is down"));
          }
        };

    // Retried like any other failure — an idp that is restarting refuses the token as readily as
    // the intake refuses the POST — but never sent bare: a guarded intake would refuse an
    // unauthenticated POST anyway, and sending one would turn a credential problem into a mystery
    // in another service's log.
    notifier.onRunSucceeded("run-3", "repo-3", "main", "c".repeat(40));
    Thread.sleep(500);
    assertEquals(0, attempts.get());
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
    notifiers.add(notifier);
    notifier.retryDelays = FAST_RETRIES;
    // A TEST-NET address nothing answers on: the 2s connect timeout belongs to the async send and
    // the backoff to a scheduler thread, so the call itself has to return immediately — it runs on
    // the single-threaded run worker.
    notifier.intakeUrl = "http://192.0.2.1:9/platform-deployments/api/events/build-succeeded";
    notifier.objectMapper = new ObjectMapper();

    long before = System.nanoTime();
    notifier.onRunSucceeded("run-4", "repo-4", "main", "d".repeat(40));
    long elapsedMillis = (System.nanoTime() - before) / 1_000_000;
    assertTrue(
        elapsedMillis < 1_000,
        "the announcement must not park the run worker (" + elapsedMillis + "ms)");
  }
}
