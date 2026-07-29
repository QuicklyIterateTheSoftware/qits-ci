package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.cidaemon.protocol.Ack;
import eu.wohlben.qits.cidaemon.protocol.Cancel;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.Heartbeat;
import eu.wohlben.qits.cidaemon.protocol.Hello;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import eu.wohlben.qits.cidaemon.protocol.Initialized;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.StepChunk;
import eu.wohlben.qits.cidaemon.protocol.StepFinished;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * The in-memory launch table: every step container qits-ci has started but not yet reaped, keyed by
 * the {@code daemonId} it was launched with, holding that launch's secret, the (run, step) it
 * belongs to, its connection once it dials, its lifecycle phase, and the listener its output goes
 * to. It is the in-JVM half of the control plane — {@link CiDaemonSocket} owns the WebSocket
 * lifecycle and forwards frames here, exactly as {@code WorkspaceDaemonRegistry} sits behind {@code
 * DaemonControlSocket} in qits-workspaces.
 *
 * <p><b>The blocking bridge.</b> Runs execute on a single-threaded worker ({@code CiRunService}),
 * one step at a time, and that thread used to park on a {@code docker run} process. It now parks
 * here instead: {@link #awaitRegistered}, {@link #awaitInitialized} and {@link #awaitFinished} each
 * block on one {@code CompletableFuture} per lifecycle transition while chunks flow to the step's
 * listener as they arrive. The failure mode that swap introduces is a future that never completes
 * wedging all of CI, so <b>every await in this package carries its transition's timeout and there
 * is no untimed {@code get()} anywhere in it</b> — {@link #await} is the single place a future is
 * waited on, it always passes a deadline, and {@code CiDaemonRegistryTimeoutTest} holds both the
 * behaviour and the absence of a second, untimed one.
 *
 * <p><b>The secret authenticates the container, and only for one thing.</b> It is minted per launch
 * from {@link SecureRandom}, injected as env, presented on the dial, compared with {@link
 * MessageDigest#isEqual} and zeroed when the launch is reaped. There is no storage beyond this map,
 * which is what makes the restart story free: a qits-ci restart forgets every secret by
 * construction, so a daemon from a previous life dialling in presents one this registry does not
 * know and is closed 1008. What the secret authorizes is exactly "deliver data about this run" — the
 * container turns hostile the moment step code runs in it, so everything arriving over that
 * connection is attacker-influenced data, recorded and never trusted (which is why timestamps are
 * host-stamped and a {@code Hello}'s {@code daemonId} is checked rather than believed).
 *
 * <p>{@link CiDaemonStepRunner} is what drives this in production, one step at a time.
 */
@ApplicationScoped
public class CiDaemonRegistry {

  private static final Logger LOG = Logger.getLogger(CiDaemonRegistry.class);

  /**
   * The handshake headers a dialling daemon presents. Identity is a header rather than a path
   * segment on purpose: the workspace control socket names its caller with a path parameter, so
   * anything on the network can claim to be any workspace's daemon (migration-plan.md §9 item 22),
   * and this socket accepts connections from containers running repo-controlled code by design. The
   * two are validated together before the first frame is read.
   */
  public static final String HEADER_ID = "X-Qits-Ci-Daemon-Id";

  public static final String HEADER_SECRET = "X-Qits-Ci-Daemon-Secret";

  /** The close code every rejected dial gets: 1008, "policy violation". */
  public static final int CLOSE_UNAUTHORIZED = 1008;

  /** How long one frame may take to leave. See {@link #send}. */
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(30);

  @Inject CiDaemonMessageCodec codec;

  private final ConcurrentHashMap<String, Launch> launches = new ConcurrentHashMap<>();

  private final SecureRandom random = new SecureRandom();

  /** What a step container is launched with: its identity and the secret that proves it. */
  public record Credentials(String daemonId, String secret) {}

  /**
   * Where a running step's output goes as it arrives — {@link CiStepRelay}, which is both the live
   * surface and the accumulator the persisted tail is read back out of.
   *
   * <p>Called on the socket's virtual thread, so it must not block for long — and a throw is
   * swallowed rather than allowed to close the connection, because a listener that fails must cost
   * its chunk and not the terminal frame behind it.
   */
  @FunctionalInterface
  public interface StepListener {
    void onChunk(Stream stream, long seq, String text);
  }

  /** How far along one launch is. Observational — the awaits are what callers actually use. */
  public enum Phase {
    /** Minted and (presumably) started; nothing has dialled. */
    LAUNCHED,
    /** A dial passed header validation. */
    CONNECTED,
    /** The daemon reported its clone and checkout done. */
    INITIALIZED,
    /** A {@link RunStep} has been sent. */
    RUNNING,
    /** A terminal frame arrived, or the launch failed to reach one. */
    DONE
  }

  /**
   * The outcome of awaiting the initialize transition. Four distinct states rather than a boolean,
   * because "the container never came up", "the daemon never got as far as a checkout", "the clone
   * failed", "the sha is gone" and "the socket dropped" are five different things a run must record
   * differently (finish-ci-feature.md §3, the transferred failure-state rule).
   */
  public record Initialization(Status status, InitFailed.Reason reason, String detail) {

    public enum Status {
      /** Clone and checkout done; the step's script is the reply to this. */
      INITIALIZED,
      /** The daemon reported a structured setup failure — {@link #reason} says which. */
      INIT_FAILED,
      /** It registered and then said nothing before the deadline. */
      NEVER_INITIALIZED,
      /** The socket closed before either. */
      CONNECTION_LOST
    }

    static Initialization ok() {
      return new Initialization(Status.INITIALIZED, null, null);
    }

    static Initialization failed(InitFailed message) {
      return new Initialization(Status.INIT_FAILED, message.reason(), message.detail());
    }

    static Initialization of(Status status) {
      return new Initialization(status, null, null);
    }
  }

  /** The outcome of awaiting the step's terminal frame. */
  public record Completion(Status status, int exitCode, boolean timedOut) {

    public enum Status {
      /** {@code StepFinished} arrived; {@link #exitCode} and {@link #timedOut} are the daemon's. */
      FINISHED,
      /** The host's backstop deadline expired with the socket still open. */
      NO_ANSWER,
      /** The socket closed before a terminal frame. */
      CONNECTION_LOST
    }

    static Completion finished(StepFinished message) {
      return new Completion(Status.FINISHED, message.exitCode(), message.timedOut());
    }

    static Completion of(Status status) {
      return new Completion(status, -1, false);
    }
  }

  /** Why a dial was refused, or that it was not. */
  public enum Admission {
    ADMITTED,
    /** No launch record with that id — a stale daemon from before a restart, or a stranger. */
    UNKNOWN_DAEMON,
    /** The id exists and the secret does not match it. */
    BAD_SECRET,
    /** That launch already has an open connection; a second one is not a reconnect, it is a claim. */
    ALREADY_CONNECTED
  }

  // --- the launch side (called by the launcher / the runner's worker thread) ----------------------

  /**
   * Mint an identity and a secret for one step container and record the launch. Called immediately
   * before {@code docker run}, so the record exists before anything can dial against it.
   */
  public Credentials registerLaunch(String runId, int stepIndex, StepListener listener) {
    String daemonId = UUID.randomUUID().toString();
    byte[] entropy = new byte[32];
    random.nextBytes(entropy);
    String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    launches.put(daemonId, new Launch(daemonId, secret, runId, stepIndex, listener));
    LOG.debugf("Minted ci-daemon %s for run %s step %d", daemonId, runId, stepIndex);
    return new Credentials(daemonId, secret);
  }

  /**
   * Block until the container's daemon dials and passes header validation. False means it never did
   * — the never-registered state, whose diagnosis is the bootstrap's own output and therefore a
   * {@code docker logs} tail captured <em>before</em> the container is reaped.
   */
  public boolean awaitRegistered(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    return launch != null && Boolean.TRUE.equals(await(launch.registered, timeout, Boolean.FALSE));
  }

  /** Block until the daemon reports its checkout done, fails it, drops, or the deadline expires. */
  public Initialization awaitInitialized(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Initialization.of(Initialization.Status.CONNECTION_LOST);
    }
    return await(
        launch.initialized, timeout, Initialization.of(Initialization.Status.NEVER_INITIALIZED));
  }

  /**
   * Send this container's one and only {@link RunStep}. The correlation id is returned so the caller
   * can tie chunks and the terminal frame to it — one step per container makes it unambiguous today,
   * and it exists from the first version so moving output to a second socket later never changes a
   * message's shape.
   */
  public String sendRunStep(String daemonId, String script, int timeoutSeconds) {
    Launch launch = require(daemonId);
    String correlationId = UUID.randomUUID().toString();
    launch.correlationId = correlationId;
    launch.phase = Phase.RUNNING;
    send(launch, new RunStep(correlationId, script, timeoutSeconds));
    return correlationId;
  }

  /** Block until the step's terminal frame, the socket's loss, or the host's backstop deadline. */
  public Completion awaitFinished(String daemonId, Duration timeout) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Completion.of(Completion.Status.CONNECTION_LOST);
    }
    return await(launch.finished, timeout, Completion.of(Completion.Status.NO_ANSWER));
  }

  /**
   * Ask the daemon to kill its child. It answers with {@link StepFinished}, so the in-flight {@link
   * #awaitFinished} completes normally instead of timing out on a socket the host then has to reap.
   * A no-op when nothing is connected — the caller reaps either way.
   */
  public void cancel(String daemonId) {
    Launch launch = launches.get(daemonId);
    if (launch == null || launch.correlationId == null) {
      return;
    }
    send(launch, new Cancel(launch.correlationId));
  }

  /**
   * Forget a launch: close its socket, complete anything still pending so no await can outlive the
   * record, and zero the secret. Called on every teardown path — the secret's lifetime is the
   * container's, and after this a dial presenting it is closed 1008 like any stranger's.
   */
  public void reap(String daemonId) {
    Launch launch = launches.remove(daemonId);
    if (launch == null) {
      return;
    }
    launch.phase = Phase.DONE;
    launch.registered.complete(Boolean.FALSE);
    launch.initialized.complete(Initialization.of(Initialization.Status.CONNECTION_LOST));
    launch.finished.complete(Completion.of(Completion.Status.CONNECTION_LOST));
    WebSocketConnection connection = launch.connection;
    if (connection != null && connection.isOpen()) {
      try {
        connection.closeAndAwait();
      } catch (RuntimeException e) {
        LOG.debugf("Closing the socket of reaped ci-daemon %s failed: %s", daemonId, e.getMessage());
      }
    }
    Arrays.fill(launch.secret, (byte) 0);
  }

  /** Observational: how far a launch got, or null once it is reaped. */
  public Phase phaseOf(String daemonId) {
    Launch launch = launches.get(daemonId);
    return launch == null ? null : launch.phase;
  }

  /** Observational: how many launches are on the books. Zero after a clean run. */
  public int size() {
    return launches.size();
  }

  // --- the socket side --------------------------------------------------------------------------

  /**
   * Validate a dial's two headers against the launch table and, when they hold, bind the connection
   * to that launch. Everything here happens before a single frame is processed, and an unadmitted
   * connection is closed 1008 by the caller.
   *
   * <p>Atomic in the id, so two simultaneous dials for one launch cannot both be admitted: the
   * second is {@link Admission#ALREADY_CONNECTED}, which is a re-dial claim rather than a reconnect.
   * A ci daemon has one container lifetime and one step, so it has nothing to reconnect for; a
   * second socket on one launch would be a second party wanting to speak for it.
   */
  public Admission admit(String daemonId, String secret, WebSocketConnection connection) {
    if (daemonId == null || secret == null) {
      return Admission.UNKNOWN_DAEMON;
    }
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return Admission.UNKNOWN_DAEMON;
    }
    synchronized (launch) {
      if (!MessageDigest.isEqual(launch.secret, secret.getBytes(StandardCharsets.UTF_8))) {
        return Admission.BAD_SECRET;
      }
      if (launch.connection != null && launch.connection.isOpen()) {
        return Admission.ALREADY_CONNECTED;
      }
      launch.connection = connection;
      launch.phase = Phase.CONNECTED;
    }
    launch.registered.complete(Boolean.TRUE);
    LOG.debugf(
        "ci-daemon %s registered for run %s step %d (connection %s)",
        daemonId, launch.runId, launch.stepIndex, connection.id());
    return Admission.ADMITTED;
  }

  /**
   * Handle one decoded frame. Returns false when the connection should be closed 1008 — today only
   * for a {@link Hello} whose {@code daemonId} disagrees with the connection the host already
   * authenticated. That field is a claim the host checks rather than an identity it accepts.
   */
  public boolean onMessage(
      String daemonId, WebSocketConnection connection, CiDaemonMessage message) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      LOG.debugf("Frame for reaped ci-daemon %s dropped: %s", daemonId, message.getClass());
      return true;
    }
    switch (message) {
      case Hello hello -> {
        if (!daemonId.equals(hello.daemonId())) {
          LOG.warnf(
              "ci-daemon %s said hello as '%s' — closing the connection",
              daemonId, hello.daemonId());
          return false;
        }
        if (hello.capabilityVersion() != CiDaemonProtocol.CAPABILITY_VERSION) {
          // Logged, not refused: the Ack carries the host's version and the daemon is the side that
          // decides it cannot speak it (it exits nonzero, and its container log is the diagnosis).
          LOG.warnf(
              "ci-daemon %s announced capability %d, this host speaks %d",
              daemonId, hello.capabilityVersion(), CiDaemonProtocol.CAPABILITY_VERSION);
        }
        send(launch, new Ack(CiDaemonProtocol.CAPABILITY_VERSION));
      }
      case Heartbeat ignored -> {
        /* liveness only — the open socket is the signal */
      }
      case Initialized ignored -> {
        launch.phase = Phase.INITIALIZED;
        launch.initialized.complete(Initialization.ok());
      }
      case InitFailed failed -> {
        launch.phase = Phase.DONE;
        launch.initialized.complete(Initialization.failed(failed));
      }
      case StepChunk chunk -> relay(launch, chunk);
      case StepFinished finished -> {
        launch.phase = Phase.DONE;
        launch.finished.complete(Completion.finished(finished));
      }
      // Host → daemon messages are never received here; ignore defensively.
      case Ack ignored -> {}
      case RunStep ignored -> {}
      case Cancel ignored -> {}
    }
    return true;
  }

  /**
   * A connection went away. Whatever the worker thread is parked on completes as {@code
   * CONNECTION_LOST} immediately rather than burning its remaining deadline — a lost socket is a
   * distinguishable outcome, not a slow one. The launch record itself survives: the caller still has
   * to reap the container, and may still want a {@code docker logs} tail off it.
   */
  public void onClose(String daemonId, WebSocketConnection connection) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      return;
    }
    synchronized (launch) {
      WebSocketConnection bound = launch.connection;
      if (bound == null || !bound.id().equals(connection.id())) {
        return;
      }
      launch.connection = null;
    }
    launch.initialized.complete(Initialization.of(Initialization.Status.CONNECTION_LOST));
    launch.finished.complete(Completion.of(Completion.Status.CONNECTION_LOST));
    LOG.debugf("ci-daemon %s disconnected (connection %s)", daemonId, connection.id());
  }

  // --- internals --------------------------------------------------------------------------------

  /**
   * The one place a future is waited on in this package, and it always carries a deadline. A
   * timeout, an interrupt and a failed future all yield {@code onFailure} — the caller's job is to
   * record a distinguishable outcome and reap, never to wait longer.
   */
  private static <T> T await(CompletableFuture<T> future, Duration timeout, T onFailure) {
    try {
      return future.get(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return onFailure;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return onFailure;
    } catch (ExecutionException e) {
      LOG.debugf("A ci-daemon await failed rather than timing out: %s", e.getCause());
      return onFailure;
    }
  }

  /**
   * Hand one chunk to the step's listener, asserting the per-correlation sequence on the way past. A
   * gap is logged and the chunk still delivered: {@code seq} exists so the host can tell "the step
   * printed nothing" from "we lost frames", not so it can drop output.
   */
  private void relay(Launch launch, StepChunk chunk) {
    long expected = launch.lastSeq + 1;
    if (chunk.seq() != expected && launch.lastSeq >= 0) {
      LOG.warnf(
          "ci-daemon %s chunk seq %d, expected %d — output may be missing",
          launch.daemonId, chunk.seq(), expected);
    }
    launch.lastSeq = Math.max(launch.lastSeq, chunk.seq());
    if (launch.listener == null) {
      return;
    }
    try {
      launch.listener.onChunk(chunk.stream(), chunk.seq(), chunk.text());
    } catch (RuntimeException e) {
      LOG.debugf("ci-daemon %s chunk listener failed (dropped): %s", launch.daemonId, e.getMessage());
    }
  }

  /**
   * Write one frame, bounded. {@code sendTextAndAwait} would be the precedent's spelling and is
   * exactly {@code sendText(m).await().indefinitely()} — an untimed block on a socket whose peer is
   * a container running repo-controlled code. The same rule that forbids an untimed {@code get()}
   * here forbids that: a peer that stops draining its side must cost this send its deadline and no
   * more, never the run worker forever.
   */
  private void send(Launch launch, CiDaemonMessage message) {
    WebSocketConnection connection = launch.connection;
    if (connection == null || !connection.isOpen()) {
      LOG.debugf("No live socket for ci-daemon %s — dropped %s", launch.daemonId, message.getClass());
      return;
    }
    try {
      connection.sendText(codec.encode(message)).await().atMost(SEND_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.warnf("Could not send %s to ci-daemon %s: %s", message.getClass().getSimpleName(),
          launch.daemonId, e.getMessage());
    }
  }

  private Launch require(String daemonId) {
    Launch launch = launches.get(daemonId);
    if (launch == null) {
      throw new IllegalStateException("No ci-daemon launch record for " + daemonId);
    }
    return launch;
  }

  /** One launched step container: the credentials it holds and the transitions it owes the host. */
  private static final class Launch {

    private final String daemonId;

    /**
     * Held as bytes rather than a String so {@link MessageDigest#isEqual} compares it without a
     * conversion at every dial, and so {@link #reap} can actually zero it — a String would leave the
     * secret in the heap until the collector felt like it.
     */
    private final byte[] secret;

    private final String runId;
    private final int stepIndex;
    private final StepListener listener;

    private volatile WebSocketConnection connection;
    private volatile Phase phase = Phase.LAUNCHED;
    private volatile String correlationId;
    private volatile long lastSeq = -1;

    private final CompletableFuture<Boolean> registered = new CompletableFuture<>();
    private final CompletableFuture<Initialization> initialized = new CompletableFuture<>();
    private final CompletableFuture<Completion> finished = new CompletableFuture<>();

    Launch(String daemonId, String secret, String runId, int stepIndex, StepListener listener) {
      this.daemonId = daemonId;
      this.secret = secret.getBytes(StandardCharsets.UTF_8);
      this.runId = runId;
      this.stepIndex = stepIndex;
      this.listener = listener;
    }
  }
}
