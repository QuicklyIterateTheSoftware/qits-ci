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
 */
public final class FakeCiDaemon implements AutoCloseable {

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
              .setURI(endpoint.getPath());
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
