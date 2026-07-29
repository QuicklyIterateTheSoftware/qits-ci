package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The endpoint each step container's {@code qits-ci-daemon} dials on boot. It owns only the
 * WebSocket lifecycle and JSON framing — {@link CiDaemonRegistry} owns the launch table, the state
 * and the correlated traffic — which is the {@code DaemonControlSocket} split in qits-workspaces,
 * kept deliberately.
 *
 * <p><b>The path literal carries {@code /ci} itself.</b> A {@code @WebSocket} path registers
 * straight onto the router and does <em>not</em> follow {@code quarkus.rest.path}, so the segment
 * that every route of this service must serve has to be spelled here. {@code daemon} is a
 * second-level segment beside {@code api} because this is not a JSON API. It is also outside {@code
 * CiTokenFilter}'s reach by construction — that filter matches {@code UriInfo.getPath()}, which is
 * relative to {@code quarkus.rest.path} — and that is correct rather than an oversight: this
 * socket's callers are containers holding no intake token, and its authentication is the
 * per-container secret below.
 *
 * <p><b>The address is a cross-repo contract.</b> {@code CiDaemonLauncher} injects {@code
 * qits.ci.container-daemon-url} (default {@code ws://qits-ci:8080/ci/daemon}) as {@code
 * $QITS_CI_DAEMON_URL} into every step container, and qits-ci-daemon dials exactly that string
 * verbatim. Move this path and that default moves with it. It is dialled directly on {@code
 * qits.ci.network} at this service's own port: a daemon is never a gateway route — one process per
 * container with a lifetime of one step has no stable address to configure.
 *
 * <p><b>Nothing is trusted before the headers are.</b> {@code @OnOpen} validates {@code
 * X-Qits-Ci-Daemon-Id} and {@code X-Qits-Ci-Daemon-Secret} out of the handshake against the launch
 * table and closes 1008 on an unknown id, a wrong secret, or a re-dial for a launch already
 * connected — before a single frame is processed. Identity is not in the path, deliberately: the
 * workspace control socket takes its caller's identity from a path parameter, which is its known
 * impersonation bug (migration-plan.md §9 item 22), and this socket accepts connections from
 * containers running repo-controlled code by design.
 *
 * <p>Frames are handled on virtual threads, so a step spraying output cannot occupy an event loop,
 * and an undecodable frame is caught and logged rather than allowed to kill the connection — the
 * shared codec throws on an unknown type and on an unknown {@code InitFailed} reason and NPEs on an
 * absent {@code stream}, strictness that is right for the contract and fatal if one malformed frame
 * from a container took the socket with it.
 */
@WebSocket(path = "/ci/daemon")
public class CiDaemonSocket {

  private static final Logger LOG = Logger.getLogger(CiDaemonSocket.class);

  /**
   * The daemon id this connection was admitted under, stashed on the connection at open. The
   * alternative — scanning the launch table for a matching connection id on every frame — would make
   * a chatty step's cost depend on how many containers are in flight.
   */
  private static final UserData.TypedKey<String> DAEMON_ID = UserData.TypedKey.forString("daemonId");

  @Inject CiDaemonRegistry registry;

  @Inject CiDaemonMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(WebSocketConnection connection) {
    String daemonId = connection.handshakeRequest().header(CiDaemonRegistry.HEADER_ID);
    String secret = connection.handshakeRequest().header(CiDaemonRegistry.HEADER_SECRET);
    CiDaemonRegistry.Admission admission = registry.admit(daemonId, secret, connection);
    if (admission != CiDaemonRegistry.Admission.ADMITTED) {
      // Deliberately the same close code and no detail for all three: a caller that guessed wrong
      // learns that it was wrong, not which half of the credential it got right.
      LOG.warnf(
          "Refused a ci-daemon dial from %s as '%s': %s",
          connection.handshakeRequest().remoteAddress(), daemonId, admission);
      close(connection, admission.name());
      return;
    }
    connection.userData().put(DAEMON_ID, daemonId);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    String daemonId = connection.userData().get(DAEMON_ID);
    if (daemonId == null) {
      // Not admitted (the close from @OnOpen may still be in flight) — read nothing from it.
      return;
    }
    CiDaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf("Dropped an undecodable frame from ci-daemon %s: %s", daemonId, e.getMessage());
      return;
    }
    if (!registry.onMessage(daemonId, connection, decoded)) {
      close(connection, "IMPERSONATION");
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    String daemonId = connection.userData().get(DAEMON_ID);
    if (daemonId != null) {
      registry.onClose(daemonId, connection);
    }
  }

  /**
   * Refuse a dial, with a deadline on the refusal.
   *
   * <p>Bounded through {@link CiDaemonRegistry#closeBounded} rather than {@code closeAndAwait} for
   * the package's one rule: that convenience is {@code close().await().indefinitely()}, and the peer
   * being refused here is by definition one this host has no reason to trust — an unknown id, a
   * wrong secret, or something claiming a launch that is already connected. A caller that could hang
   * the refusal could pin a virtual thread per dial simply by never completing the handshake.
   */
  private void close(WebSocketConnection connection, String reason) {
    CiDaemonRegistry.closeBounded(
        connection,
        new CloseReason(CiDaemonRegistry.CLOSE_UNAUTHORIZED, reason),
        "a refused ci-daemon dial");
  }
}
