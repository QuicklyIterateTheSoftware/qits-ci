package eu.wohlben.qits.eventsourcing.control;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The listening half: one outbound websocket to qits-events' {@code /events/stream}, a subscribe
 * frame naming every signature some {@link eu.wohlben.qits.eventsourcing.QitsEventListener} bean
 * asked for, and every matching broadcast handed to {@link EventDispatcher}.
 *
 * <p><b>It dials out and never listens.</b> The address is derived from {@code qits.events.url} —
 * the same value the publisher's {@code PUT} uses, with {@code http}/{@code https} swapped for
 * {@code ws}/{@code wss} — because one service has one address and two config keys for it is two
 * things to get out of step. The path is the literal {@code /events/stream}, which carries its own
 * {@code /events} segment for the reason every {@code @WebSocket} path in this platform does: it
 * follows no {@code quarkus.rest.path}.
 *
 * <p><b>Reconnect is unbounded, unlike qits-ci-daemon's control socket</b>, and the inversion is
 * deliberate. That socket dials once because it exists for one step's worth of conversation and a
 * close is a terminal condition. This one is the process's ear on the platform for as long as the
 * process runs, so a close is qits-events restarting and the only correct answer is to come back.
 * The backoff is capped, and the subscribe frame is re-sent on every open — the server holds the
 * subscription set per connection, so a reconnect that did not resubscribe would be a socket that
 * is up and deaf.
 *
 * <p>Nothing is dialled when the module is disabled, and nothing is dialled when the application
 * registered no listener at all — an open stream nobody reads is a connection qits-events maintains
 * for no one.
 */
@ApplicationScoped
public class EventStreamSubscriber {

  private static final Logger LOG = Logger.getLogger(EventStreamSubscriber.class);

  /** qits-events' stream endpoint. A literal, and the other half of a cross-repo contract. */
  static final String STREAM_PATH = "/events/stream";

  @Inject EventDispatcher dispatcher;

  @Inject Vertx vertx;

  @ConfigProperty(name = "qits.eventsourcing.enabled")
  boolean enabled;

  @ConfigProperty(name = "qits.events.url")
  String eventsUrl;

  @ConfigProperty(name = "qits.eventsourcing.redial-initial-backoff", defaultValue = "PT1S")
  Duration redialInitialBackoff;

  @ConfigProperty(name = "qits.eventsourcing.redial-max-backoff", defaultValue = "PT30S")
  Duration redialMaxBackoff;

  private final AtomicInteger consecutiveFailures = new AtomicInteger();

  /** One redial timer at a time. A failed connect and a close can both ask; only the first arms it. */
  private final AtomicBoolean redialArmed = new AtomicBoolean();

  private volatile boolean running;
  private volatile WebSocketClientConnection connection;

  void onStart(@Observes StartupEvent ignored) {
    if (!enabled) {
      LOG.debug("eventsourcing disabled: not dialling the event stream");
      return;
    }
    if (dispatcher.signatures().isEmpty()) {
      LOG.debug("no QitsEventListener beans: not dialling the event stream");
      return;
    }
    start();
  }

  void onStop(@Observes ShutdownEvent ignored) {
    stop();
  }

  /** Begin dialling, and keep the stream up until {@link #stop()}. */
  public void start() {
    running = true;
    consecutiveFailures.set(0);
    dial();
  }

  /** Stop dialling and drop the current stream. Idempotent. */
  public void stop() {
    running = false;
    WebSocketClientConnection open = connection;
    connection = null;
    if (open != null && open.isOpen()) {
      // Bounded, and shorter than a send would get: the peer this is being polite to is one we are
      // walking away from either way.
      try {
        open.close().await().atMost(CLOSE_TIMEOUT);
      } catch (RuntimeException e) {
        LOG.debugf("event stream close did not complete: %s", e.getMessage());
      }
    }
  }

  /** Whether the stream is up right now — the subscriber's own liveness, for tests and health. */
  public boolean connected() {
    WebSocketClientConnection open = connection;
    return open != null && open.isOpen();
  }

  private void dial() {
    if (!running) {
      return;
    }
    URI base = streamBaseUri();
    LOG.debugf("dialling the event stream at %s%s", base, STREAM_PATH);
    BasicWebSocketConnector.create()
        .baseUri(base)
        .path(STREAM_PATH)
        // A listener's onEvent is arbitrary application code and is allowed to block; the frame
        // callback therefore must not run on an event loop.
        .executionModel(BasicWebSocketConnector.ExecutionModel.BLOCKING)
        .onOpen(this::onOpen)
        .onTextMessage((connection, text) -> dispatcher.dispatch(text))
        .onError((connection, failure) -> LOG.debugf("event stream error: %s", failure.toString()))
        .onClose((connection, reason) -> onClose(reason == null ? "" : reason.toString()))
        .connect()
        .subscribe()
        .with(
            opened -> {},
            failure -> {
              LOG.debugf("event stream dial failed: %s", failure.toString());
              redial();
            });
  }

  private void onOpen(WebSocketClientConnection opened) {
    connection = opened;
    consecutiveFailures.set(0);
    subscribe(opened);
  }

  private void onClose(String reason) {
    connection = null;
    if (!running) {
      return;
    }
    LOG.debugf("event stream closed (%s); redialling", reason);
    redial();
  }

  /**
   * {@code {"subscribe":["A","B"]}} — replaces this connection's subscription set. Sent on every
   * open, including reconnects, because the set lives on the connection and dies with it.
   *
   * <p>Fired without waiting: this runs on the connector's callback and the answer to a send that
   * did not go out is the same close-and-redial as any other drop.
   */
  private void subscribe(WebSocketClientConnection open) {
    List<String> signatures = dispatcher.signatures();
    String frame = CanonicalJson.subscribeFrame(signatures);
    open.sendText(frame)
        .subscribe()
        .with(
            sent -> LOG.debugf("subscribed to %s", signatures),
            failure -> LOG.debugf("subscribe frame not sent: %s", failure.toString()));
  }

  private void redial() {
    if (!running) {
      return;
    }
    if (!redialArmed.compareAndSet(false, true)) {
      return;
    }
    Duration delay =
        RetrySchedule.redialBackoff(
            consecutiveFailures.getAndIncrement(), redialInitialBackoff, redialMaxBackoff);
    vertx.setTimer(
        Math.max(1L, delay.toMillis()),
        ignored -> {
          redialArmed.set(false);
          dial();
        });
  }

  /**
   * {@code qits.events.url} is scheme+host+port with no path — the platform shape — so the dial
   * address is that with the websocket scheme. Rebuilt rather than string-replaced so a malformed
   * value fails here, loudly, instead of producing an address that looks plausible.
   */
  URI streamBaseUri() {
    URI url = URI.create(eventsUrl.trim());
    String scheme = url.getScheme() == null ? "http" : url.getScheme().toLowerCase();
    String wsScheme = SCHEMES.getOrDefault(scheme, scheme);
    if (url.getHost() == null) {
      throw new IllegalStateException("qits.events.url has no host: '" + eventsUrl + "'");
    }
    String port = url.getPort() == -1 ? "" : ":" + url.getPort();
    return URI.create(wsScheme + "://" + url.getHost() + port);
  }

  private static final Map<String, String> SCHEMES = Map.of("http", "ws", "https", "wss");

  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);
}
