package eu.wohlben.qits.ci.bus;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A qits-events that never leaves this JVM: a real Vert.x server answering the real {@code PUT
 * /events/api/events/{id}} with a 201 and accepting a real upgrade on {@code /events/stream}, on an
 * ephemeral port handed to Quarkus as {@code qits.events.url} <b>before it boots</b> — which is the
 * only way a port that cannot be known earlier reaches the application's config. Same arrangement
 * (and same reason) as qits-gateway's {@code StubUpstream}.
 *
 * <p><b>A second, smaller copy of the eventstream module's stub, and deliberately so.</b> The two
 * modules do not share a test classpath — the same reason {@code FakeCiStepRunner} exists twice in
 * this repo — and publishing a test-jar to bridge them would couple this suite to that module's
 * fixtures for the sake of forty lines. This copy is trimmed to what an integration test needs: it
 * scripts nothing, always answers 201, and records what arrived. Failure handling, backoff and the
 * three-way PUT semantics are the eventstream suite's subject, not this one's.
 *
 * <p>The two literals below are the wire contract's, spelled out rather than imported: the
 * constants in the eventstream module are package-private, and a stub standing in for another
 * <em>service</em> should be written against the contract anyway. If they ever disagree, this
 * suite's PUT never arrives — which is exactly the failure a wrong path would cause in a
 * deployment.
 */
public class StubEventsServer implements QuarkusTestResourceLifecycleManager {

  /** qits-events' idempotent publish endpoint, up to and including the trailing slash. */
  private static final String EVENTS_PATH = "/events/api/events/";

  /** qits-events' broadcast stream. Accepted so the subscriber settles instead of redialling. */
  private static final String STREAM_PATH = "/events/stream";

  /** One request that arrived: the id from the path, and the body verbatim. */
  public record Put(String id, String body) {}

  /** One event a test seeds for {@code GET} to answer -- {@link DaemonReleaseListener}'s startup
   *  discovery is the one reader in this repository, and this is the whole shape it reads. */
  public record Seeded(String id, String occurredAt, String payload) {}

  private static final List<Put> PUTS = Collections.synchronizedList(new ArrayList<>());

  private static final List<Seeded> SEEDED_EVENTS = Collections.synchronizedList(new ArrayList<>());

  /**
   * Every subscribe frame this stub was sent. Recorded because the deployable's own wire set is a
   * claim worth one assertion here: the trigger engine's raw listener says {@code "*"} permanently,
   * which collapses the whole union to {@code ["*"]} — so {@code BuildSuccessfulListener} stops
   * appearing on the wire and keeps working, since dispatch filters and the wire never did.
   */
  private static final List<String> SUBSCRIBES = Collections.synchronizedList(new ArrayList<>());

  private static Vertx vertx;
  private static HttpServer server;

  /** Every PUT that arrived, in order. */
  public static List<Put> puts() {
    synchronized (PUTS) {
      return List.copyOf(PUTS);
    }
  }

  /** Every subscribe frame that arrived, in order, verbatim. */
  public static List<String> subscribes() {
    synchronized (SUBSCRIBES) {
      return List.copyOf(SUBSCRIBES);
    }
  }

  /**
   * Forget what arrived, and forget what {@code GET} would answer. Called between tests; the server
   * itself is one per JVM.
   *
   * <p>Subscribes are deliberately <b>not</b> cleared: there is one per connection and the connection
   * outlives every test method, so clearing would throw away the only copy of the frame.
   */
  public static void reset() {
    PUTS.clear();
    SEEDED_EVENTS.clear();
  }

  /**
   * Script what {@code GET /events/api/events} answers -- a {@code SoftwareRelease}'s three fields
   * {@link eu.wohlben.qits.ci.control.DaemonReleaseLog} reads, in the order they are added. This stub
   * ignores every query parameter and returns the whole scripted list: {@code EventsDaemonReleaseLog}
   * asks for {@code limit=2} of a query qits-events itself already filters and orders (BU), so
   * scripting the two rows a test wants read is the honest shape for a stub standing in for that
   * service, not a second implementation of its filter.
   */
  public static void seedEvent(String id, String occurredAt, String payload) {
    SEEDED_EVENTS.add(new Seeded(id, occurredAt, payload));
  }

  @Override
  public Map<String, String> start() {
    vertx = Vertx.vertx();
    server =
        vertx
            .createHttpServer()
            // A Vert.x server carrying only a webSocketHandler NPEs on any plain request, and this
            // one has to answer both — the PUT and the upgrade are the same service.
            .requestHandler(
                request -> {
                  String path = request.path();
                  if (request.method().name().equals("PUT") && path.startsWith(EVENTS_PATH)) {
                    String id = path.substring(EVENTS_PATH.length());
                    request
                        .body()
                        .onSuccess(
                            body -> {
                              PUTS.add(new Put(id, body.toString()));
                              request.response().setStatusCode(201).end();
                            });
                    return;
                  }
                  if (request.method().name().equals("GET")
                      && path.equals(EVENTS_PATH.substring(0, EVENTS_PATH.length() - 1))) {
                    request
                        .response()
                        .putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(listResponse());
                    return;
                  }
                  request.response().setStatusCode(404).end();
                })
            .webSocketHandler(
                socket -> {
                  if (!STREAM_PATH.equals(socket.path())) {
                    socket.reject();
                    return;
                  }
                  // The subscribe frame is recorded and nothing is ever broadcast back: what a
                  // broadcast does on arrival is the eventstream suite's dispatch test and the live
                  // platform's end-to-end proof, and a test here that needs a frame delivers it
                  // through EventDispatcher directly. Accepting the upgrade is what keeps the
                  // subscriber from redialling through the whole test.
                  socket.textMessageHandler(SUBSCRIBES::add);
                });
    server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
    return Map.of("qits.events.url", "http://127.0.0.1:" + server.actualPort());
  }

  /** The {@code {"events":[...],"nextCursor":null}} shape {@code EventController.list} answers,
   *  built from whatever {@link #seedEvent} scripted. */
  private static String listResponse() {
    StringBuilder body = new StringBuilder("{\"events\":[");
    synchronized (SEEDED_EVENTS) {
      for (int i = 0; i < SEEDED_EVENTS.size(); i++) {
        Seeded seeded = SEEDED_EVENTS.get(i);
        if (i > 0) {
          body.append(',');
        }
        body.append("{\"id\":")
            .append(Json.encode(seeded.id()))
            .append(",\"name\":\"SoftwareRelease\",\"occurredAt\":")
            .append(Json.encode(seeded.occurredAt()))
            .append(",\"payload\":")
            .append(Json.encode(seeded.payload()))
            .append(",\"description\":null,\"parentId\":null,\"createdAt\":")
            .append(Json.encode(seeded.occurredAt()))
            .append(",\"updatedAt\":")
            .append(Json.encode(seeded.occurredAt()))
            .append("}");
      }
    }
    body.append("],\"nextCursor\":null}");
    return body.toString();
  }

  @Override
  public void stop() {
    if (server != null) {
      server.close().toCompletionStage().toCompletableFuture().join();
    }
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture().join();
    }
  }
}
