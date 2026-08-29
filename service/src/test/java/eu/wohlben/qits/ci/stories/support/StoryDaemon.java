package eu.wohlben.qits.ci.stories.support;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.ci.daemonhost.FakeCiDaemon;
import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Heartbeat;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.net.URI;
import java.time.Duration;

/**
 * The step container's own {@code qits-ci-daemon}, as a story drives it — and the tap for the one
 * plane the framework ships no tap for.
 *
 * <h2>Why this is a real client and not a fixture</h2>
 *
 * <p>{@link FakeCiDaemon} is a real Vert.x WebSocket dialling the real endpoint with the real
 * handshake headers and framing the real protocol through the vendored {@code CiDaemonCodec} — the
 * host cannot tell it from a container. What this class adds is the <b>credential's provenance</b>
 * and the <b>edges</b>. The id and the secret come out of the workload spec qits-ci sent to
 * qits-containers ({@link MockContainers#awaitLaunchEnvironment}), which is exactly where a
 * container gets them and the only place they exist: nothing in the story reads the host's launch
 * table, so an admitted dial here is evidence that the credential really travelled the way the
 * service says it does.
 *
 * <h2>The handshake carries two credentials, not one</h2>
 *
 * <p>{@code CiDaemonSocket} is annotated {@code @RolesAllowed("qits:system")}, enforced at the HTTP
 * <b>upgrade</b>, so the container's per-launch secret is not on its own enough to get through the
 * door: the daemon also asserts {@code X-Qits-User: qits-ci-daemon} / {@code X-Qits-Roles:
 * qits:system}, which it may because the dial is intra-network and never crosses the edge that
 * strips that header namespace. {@link FakeCiDaemon} sends both pairs for that reason, and the two
 * do different jobs — the role opens the route, the secret says <em>which launch</em> this is, and
 * only the second one is checked against anything qits-ci minted.
 *
 * <h2>The tap, and why it is written here</h2>
 *
 * <p>The framework ships a RestAssured tap and nothing for a socket, so this plane is instrumented
 * with {@link NetworkCapture#observe} at the call sites — and every one of those calls is
 * synchronous on the <b>story thread</b>, which is the one place the framework's rule allows the
 * actor to be read. A handler on Vert.x' event loop would inherit whatever actor is current when
 * the frame lands, which is a different story's.
 *
 * <p>Two kinds, and the split is the vocabulary's own:
 *
 * <ul>
 *   <li><b>{@code socket}</b> — the dial. One edge, recorded once, for the connection the container
 *       holds open. Direction is who dialled, and the whole design of this plane is that the
 *       container dials <em>out</em>: qits-ci never dials in, which is why a step container needs
 *       no address and no inbound route.
 *   <li><b>{@code event}</b> — one per frame pushed over that connection, in whichever direction it
 *       was pushed. {@code hello}/{@code initialized}/{@code stepChunk}/{@code stepFinished} are
 *       the daemon's; {@code ack} and {@code runStep} are the host's, and {@code runStep} is the
 *       interesting one: <b>the step is the reply to the daemon's own {@code Initialized}</b>, so
 *       the arrow into the container exists only because the container asked for work.
 * </ul>
 *
 * <p>Labels are the protocol's own type names plus values that cannot vary between runs (a
 * capability version, a stream name, an exit code). A correlation id is deliberately not in any of
 * them: it is minted per step and would move the story's {@code networkHash} on every run.
 */
public final class StoryDaemon implements AutoCloseable {

  /** How the diagram names the initiator of everything on this plane. */
  public static final String ACTOR = "a build daemon";

  /** The environment variable the daemon reads its identity out of. */
  public static final String ID_VARIABLE = "QITS_CI_DAEMON_ID";

  /** …and its one-container-lifetime credential. */
  public static final String SECRET_VARIABLE = "QITS_CI_DAEMON_SECRET";

  /** The address a container dials, injected as {@code $QITS_CI_DAEMON_URL}. */
  public static final String URL_VARIABLE = "QITS_CI_DAEMON_URL";

  /** How long a frame the host owes may take to arrive. Generous: the host is a launched process. */
  private static final Duration SOON = Duration.ofSeconds(30);

  private final FakeCiDaemon socket;

  private StoryDaemon(FakeCiDaemon socket) {
    this.socket = socket;
  }

  /**
   * Dial the control socket with one step container's credentials, and record the connection.
   *
   * <p>The upgrade completing is not admission: a refused dial is a 1008 <b>close</b> after a
   * successful upgrade, because the host validates the two headers in {@code @OnOpen} and closes
   * rather than failing a handshake. So the edge is recorded here — the connection was made — and
   * whether it was kept is what the story's own assertions say.
   */
  public static StoryDaemon dial(URI endpoint, String daemonId, String secret) throws Exception {
    assertNotNull(daemonId, ID_VARIABLE + " was not in the workload spec");
    assertNotNull(secret, SECRET_VARIABLE + " was not in the workload spec");
    FakeCiDaemon socket = FakeCiDaemon.dial(endpoint, daemonId, secret);
    pushed(ACTOR, StoryTarget.SERVICE, NetworkEdge.SOCKET, "CONNECT " + StoryTarget.DAEMON_PATH);
    return new StoryDaemon(socket);
  }

  /** {@code Hello} — the daemon naming itself, which the host checks against the connection. */
  public void hello(String daemonId) throws Exception {
    socket.send(new Hello(daemonId, CiDaemonProtocol.CAPABILITY_VERSION));
    fromDaemon("hello");
  }

  /** The host's answer, carrying the capability version a mismatched daemon exits on. */
  public Ack awaitAck() throws Exception {
    Ack ack = assertInstanceOf(Ack.class, next(), "the host must acknowledge a Hello");
    fromHost("ack capabilityVersion " + ack.capabilityVersion());
    return ack;
  }

  /** A liveness frame. The daemon sends them unprompted; the host owes nothing back. */
  public void heartbeat() throws Exception {
    socket.send(new Heartbeat());
    fromDaemon("heartbeat");
  }

  /** {@code Initialized} — the checkout is done and this container is ready for work. */
  public void initialized() throws Exception {
    socket.send(new Initialized());
    fromDaemon("initialized");
  }

  /** The step itself, which arrives as the reply to {@link #initialized()} and never before it. */
  public RunStep awaitRunStep() throws Exception {
    RunStep step =
        assertInstanceOf(RunStep.class, next(), "the step must arrive as the reply to Initialized");
    fromHost("runStep");
    return step;
  }

  /** One line of the step's output, on the stream it was written to. */
  public void chunk(String correlationId, long seq, Stream stream, String text) throws Exception {
    socket.send(new StepChunk(correlationId, seq, stream, text));
    fromDaemon("stepChunk " + stream.name());
  }

  /** The terminal frame: the step ended, with this exit code, and whether its own deadline fired. */
  public void finished(String correlationId, int exitCode, boolean timedOut) throws Exception {
    socket.send(new StepFinished(correlationId, exitCode, timedOut));
    fromDaemon("stepFinished exit " + exitCode);
  }

  /** The close code the host sent, or null if it did not close in time. */
  public Short awaitClose(Duration timeout) {
    return socket.awaitClose(timeout);
  }

  public boolean isOpen() {
    return socket.isOpen();
  }

  @Override
  public void close() {
    socket.close();
  }

  private CiDaemonMessage next() throws Exception {
    CiDaemonMessage message = socket.next(SOON);
    assertNotNull(message, "the host sent no frame within " + SOON);
    return message;
  }

  /** A frame this container pushed; the actor is read here, on the story thread. */
  private static void fromDaemon(String label) {
    pushed(ACTOR, StoryTarget.SERVICE, NetworkEdge.EVENT, label);
  }

  /** A frame the host pushed back down the connection the container opened. */
  private static void fromHost(String label) {
    pushed(StoryTarget.SERVICE, ACTOR, NetworkEdge.EVENT, label);
  }

  private static void pushed(String from, String to, String kind, String label) {
    NetworkCapture.observe(kind, from, to, label);
  }
}
