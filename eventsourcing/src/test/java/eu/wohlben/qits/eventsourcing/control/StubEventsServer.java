package eu.wohlben.qits.eventsourcing.control;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A qits-events that never leaves this JVM: a real Vert.x server answering the real {@code PUT
 * /events/api/events/{id}} and accepting a real upgrade on {@code /events/stream}, on an ephemeral
 * port handed to Quarkus as {@code qits.events.url} before it boots.
 *
 * <p>The port is why this is a {@code QuarkusTestResourceLifecycleManager} rather than a fixture a
 * test starts: the value has to exist before the application's config is read, and it cannot be
 * known before something has bound. Same arrangement qits-gateway's {@code StubUpstream} has, for
 * the same reason.
 *
 * <p><b>Deliberately dumb.</b> It holds no idempotency table and decides nothing: a test scripts
 * the status codes it wants answered and asserts on what arrived. That is what makes the
 * uninteresting cases testable at all — a 400 on the third attempt, a 200 replay after four
 * failures — and it keeps this file from becoming a second implementation of qits-events whose
 * agreement with the first nobody checks. The real semantics are Agent A's and are tested there.
 *
 * <p>State is static because the resource is one per JVM and the tests are the only readers. {@link
 * #reset()} between tests is what keeps them independent.
 */
public class StubEventsServer implements QuarkusTestResourceLifecycleManager {

  /** One request that arrived: the id from the path, and the body verbatim. */
  public record Put(String id, String body) {}

  private static final List<Put> PUTS = Collections.synchronizedList(new ArrayList<>());
  private static final Deque<Integer> SCRIPTED = new ArrayDeque<>();
  private static final List<ServerWebSocket> SOCKETS = Collections.synchronizedList(new ArrayList<>());
  private static final LinkedBlockingQueue<String> SUBSCRIBE_FRAMES = new LinkedBlockingQueue<>();

  private static volatile int defaultStatus = 201;
  private static Vertx vertx;
  private static HttpServer server;
  private static int port;

  // -- what a test drives ------------------------------------------------------------------------

  /** Forget everything and answer 201 again. */
  public static void reset() {
    PUTS.clear();
    SUBSCRIBE_FRAMES.clear();
    synchronized (SCRIPTED) {
      SCRIPTED.clear();
    }
    defaultStatus = 201;
  }

  /** Answer every PUT with this status. */
  public static void answerWith(int status) {
    synchronized (SCRIPTED) {
      SCRIPTED.clear();
    }
    defaultStatus = status;
  }

  /**
   * Answer the next PUTs with these statuses in order; once they run out, the last one repeats.
   * How "four failures then a replay" is staged without touching the clock.
   */
  public static void answerWith(int first, int... rest) {
    synchronized (SCRIPTED) {
      SCRIPTED.clear();
      SCRIPTED.add(first);
      for (int status : rest) {
        SCRIPTED.add(status);
      }
      defaultStatus = rest.length == 0 ? first : rest[rest.length - 1];
    }
  }

  /** Every PUT that arrived, in order. */
  public static List<Put> puts() {
    synchronized (PUTS) {
      return List.copyOf(PUTS);
    }
  }

  /** The next subscribe frame a client sent, or null if none arrives in time. */
  public static String awaitSubscribeFrame(Duration within) throws InterruptedException {
    return SUBSCRIBE_FRAMES.poll(within.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Push one frame to every connected subscriber, exactly as a broadcast would. */
  public static void broadcast(String frame) {
    for (ServerWebSocket socket : openSockets()) {
      socket.writeTextMessage(frame);
    }
  }

  /** Drop every stream connection — qits-events restarting, from the client's point of view. */
  public static void dropStreams() {
    for (ServerWebSocket socket : openSockets()) {
      socket.close();
    }
  }

  /** How many stream connections are open right now. */
  public static int openStreams() {
    return openSockets().size();
  }

  private static List<ServerWebSocket> openSockets() {
    synchronized (SOCKETS) {
      SOCKETS.removeIf(ServerWebSocket::isClosed);
      return List.copyOf(SOCKETS);
    }
  }

  // -- lifecycle ---------------------------------------------------------------------------------

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
                  if (request.method().name().equals("PUT")
                      && path.startsWith(EventsPublisher.EVENTS_PATH)) {
                    String id = path.substring(EventsPublisher.EVENTS_PATH.length());
                    request
                        .body()
                        .onSuccess(
                            body -> {
                              PUTS.add(new Put(id, body.toString()));
                              request.response().setStatusCode(nextStatus()).end();
                            });
                    return;
                  }
                  request.response().setStatusCode(404).end();
                })
            .webSocketHandler(
                socket -> {
                  if (!EventStreamSubscriber.STREAM_PATH.equals(socket.path())) {
                    socket.reject();
                    return;
                  }
                  SOCKETS.add(socket);
                  socket.textMessageHandler(SUBSCRIBE_FRAMES::offer);
                });
    server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().join();
    port = server.actualPort();
    return Map.of("qits.events.url", "http://127.0.0.1:" + port);
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

  private static int nextStatus() {
    synchronized (SCRIPTED) {
      Integer scripted = SCRIPTED.poll();
      return scripted == null ? defaultStatus : scripted;
    }
  }
}
