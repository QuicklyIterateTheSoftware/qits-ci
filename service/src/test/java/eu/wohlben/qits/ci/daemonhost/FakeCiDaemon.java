package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonCodec;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A ci-daemon that never leaves this JVM: a real Vert.x WebSocket client dialling the real endpoint
 * with the real handshake headers, framing the real protocol messages the same way the native binary
 * does — {@code new JsonObject(CiDaemonCodec.encode(m))} out, {@code CiDaemonCodec.decode(json)} in.
 * The host cannot tell it from a container, which is the point: {@link CiDaemonSocket} and {@link
 * CiDaemonRegistry} are provable in a docker-free suite, and only {@code CiDaemonHandshakeIT} needs
 * a published binary.
 *
 * <p>Deliberately dumb — it holds no state machine and answers nothing on its own. Each test scripts
 * the frames it wants, including the wrong ones, which is how the refused dials and the malformed
 * frame are testable at all.
 *
 * <p><b>The handshake carries FOUR headers, not two, and the pair that is easy to forget is the one
 * that gets past the door.</b> {@link CiDaemonSocket} is annotated {@code
 * @RolesAllowed("qits:system")}, which quarkus-websockets-next enforces at the HTTP <em>upgrade</em>
 * — so a dial with no identity is answered <b>401 and never reaches {@code @OnOpen} at all</b>. The
 * real binary asserts the pair itself ({@code ControlSocket.connect}: {@code X-Qits-User:
 * qits-ci-daemon}, {@code X-Qits-Roles: qits:system}), which it may because the dial is
 * intra-network and never crosses the edge that strips the {@code X-Qits-*} namespace.
 *
 * <p>Measured 2026-08-29 against a <b>deployed</b> qits-ci: a raw upgrade carrying only the two
 * ci-daemon headers comes back {@code HTTP/1.1 401 Unauthorized}. In a {@code @QuarkusTest} it does
 * not, because the forward-auth mechanism's {@code %test} synthetic {@code dev} identity already
 * holds {@code qits:system} — so this omission was invisible to every suite that runs in TEST mode
 * and cost a packaged story its socket. That is exactly the class of gap a launched-artifact test
 * exists to close, and it is why the pair is spelled here rather than at one call site.
 */
public final class FakeCiDaemon implements AutoCloseable {

  /** How the daemon names itself to the forward-auth mechanism — {@code ControlSocket}'s literal. */
  public static final String USER_HEADER = "X-Qits-User";

  public static final String DAEMON_USER = "qits-ci-daemon";

  /** …and the role {@link CiDaemonSocket} demands, asserted the same way. */
  public static final String ROLES_HEADER = "X-Qits-Roles";

  public static final String DAEMON_ROLES = "qits:system";

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket socket;
  private final BlockingQueue<CiDaemonMessage> received = new ArrayBlockingQueue<>(256);
  private final CompletableFuture<Short> closeCode = new CompletableFuture<>();

  /**
   * Dial the endpoint with the given credentials. Returns once the HTTP upgrade completed — a
   * refused dial is a 1008 <em>close</em> after a successful upgrade, not a failed handshake, so the
   * caller asserts on {@link #awaitClose} rather than on this throwing.
   */
  public static FakeCiDaemon dial(URI endpoint, String daemonId, String secret) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath())
              // The identity half of the handshake — see the class javadoc. Unconditional, because
              // it is not a credential this fixture varies: every case here is about the ci-daemon
              // pair below, and without these two none of them would get past the upgrade.
              .addHeader(USER_HEADER, DAEMON_USER)
              .addHeader(ROLES_HEADER, DAEMON_ROLES);
      if (daemonId != null) {
        options.addHeader(CiDaemonRegistry.HEADER_ID, daemonId);
      }
      if (secret != null) {
        options.addHeader(CiDaemonRegistry.HEADER_SECRET, secret);
      }
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
      return new FakeCiDaemon(vertx, client, socket);
    } catch (Exception failedToUpgrade) {
      vertx.close();
      throw failedToUpgrade;
    }
  }

  private FakeCiDaemon(Vertx vertx, WebSocketClient client, WebSocket socket) {
    this.vertx = vertx;
    this.client = client;
    this.socket = socket;
    socket.textMessageHandler(text -> received.offer(CiDaemonCodec.decode(new JsonObject(text).getMap())));
    socket.closeHandler(ignored -> closeCode.complete(socket.closeStatusCode()));
  }

  /** Send one frame, framed exactly as the binary frames it. */
  public void send(CiDaemonMessage message) throws Exception {
    socket
        .writeTextMessage(new JsonObject(CiDaemonCodec.encode(message)).encode())
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** Send text the codec cannot decode — the one thing a well-behaved client would never do. */
  public void sendRaw(String text) throws Exception {
    socket.writeTextMessage(text).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** The next frame the host sent, or null if none arrived in time. */
  public CiDaemonMessage next(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The close code the host sent, or null if it did not close in time. */
  public Short awaitClose(Duration timeout) {
    try {
      return closeCode.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception notClosed) {
      return null;
    }
  }

  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (RuntimeException ignored) {
      // already gone
    }
    client.close();
    vertx.close();
  }
}
