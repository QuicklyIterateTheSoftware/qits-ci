package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiStepRunner;
import eu.wohlben.qits.cidaemon.protocol.InitFailed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole implementation of the step seam: mint credentials → launch a container → wait for its
 * {@code qits-ci-daemon} to dial back and report its checkout done → answer with the step's script →
 * relay chunks while accumulating the bounded tail → wait for the terminal frame → reap → answer.
 *
 * <p><b>qits-ci executes nothing.</b> Nothing on this side ever runs a step's script: the script
 * leaves this process as a field of one JSON frame, on a socket the container's own daemon opened
 * outbound, and it executes as that daemon's child inside the sandbox. There is no {@code bash} and
 * no {@code docker exec} anywhere in this path — {@link CiDaemonLauncher}'s docker vocabulary is
 * container lifecycle, and its bootstrap is a constant with nothing interpolated into it.
 *
 * <p><b>Every wait has a deadline, and each covers a different thing so a hang is attributable.</b>
 * The run worker is single-threaded, so a wait that never returns wedges not this run but all of CI.
 * Register covers the image pull and the daemon download; initialize covers the daemon's own clone
 * and checkout; the step's own deadline is enforced <em>inside</em> the container and the wait here
 * sits a grace period behind it, so in the normal case the daemon reports its own timeout and the
 * host's backstop never fires. When it does fire, the daemon is not answering and the container is
 * cancelled and then removed.
 *
 * <p><b>Failure states stay distinguishable.</b> Docker refusing the launch, a container whose
 * bootstrap never produced a daemon, a daemon that registered and then went quiet, a structured
 * setup failure, and a socket lost mid-step are five different things and each returns its own
 * {@link StepOutcome}. Where a container's own output is the only account of what happened — a
 * bootstrap that could not fetch the binary, above all — a bounded {@code docker logs} tail is
 * captured <em>before</em> the reap and becomes the step's recorded output.
 */
@ApplicationScoped
public class CiDaemonStepRunner implements CiStepRunner {

  private static final Logger LOG = Logger.getLogger(CiDaemonStepRunner.class);

  @Inject CiDaemonRegistry registry;

  @Inject CiDaemonLauncher launcher;

  @Inject CiStepRelay relay;

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.daemon-init-timeout-seconds")
  long initTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.step-timeout-grace-seconds")
  long stepTimeoutGraceSeconds;

  /**
   * The container each run currently has in flight, so a cancellation arriving on an HTTP thread can
   * find something to act on. One entry per run at most — a run is a sequence of one container at a
   * time — and it is removed on every exit path.
   */
  private final ConcurrentHashMap<String, InFlight> inFlight = new ConcurrentHashMap<>();

  private record InFlight(String daemonId, String containerName) {}

  @Override
  public DaemonPin pinDaemon() {
    String version = launcher.daemonVersion();
    return new DaemonPin(version, launcher.resolveBinaryUrl(version));
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    // Before anything reaches an argv, and before a secret is minted or a relay opened: three of
    // these arrive from the unauthenticated intake and the fourth from a file in the repository
    // being tested. The launcher checks them again at the argv itself.
    CiIdentifiers.requireRepoId(spec.repoId());
    CiIdentifiers.requireBranch(spec.branch());
    CiIdentifiers.requireSha(spec.sha());
    CiIdentifiers.requireImage(spec.image());

    relay.begin(spec.runId(), spec.stepIndex());
    CiDaemonRegistry.Credentials credentials =
        registry.registerLaunch(
            spec.runId(),
            spec.stepIndex(),
            (stream, seq, text) -> {
              relay.append(spec.runId(), text);
              listener.onChunk(text);
            });
    String containerName = CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex());
    inFlight.put(spec.runId(), new InFlight(credentials.daemonId(), containerName));

    try {
      return execute(spec, listener, credentials, containerName);
    } finally {
      // Every teardown path ends the same way: the launch record (and its secret) is forgotten and
      // the container is removed, whatever it was doing.
      inFlight.remove(spec.runId());
      registry.reap(credentials.daemonId());
      launcher.reap(containerName);
    }
  }

  private StepResult execute(
      StepSpec spec,
      StepListener listener,
      CiDaemonRegistry.Credentials credentials,
      String containerName) {
    String daemonId = credentials.daemonId();

    CiDaemonLauncher.Launched launched =
        launcher.launch(
            new CiDaemonLauncher.LaunchSpec(
                spec.runId(),
                spec.stepIndex(),
                spec.repoId(),
                spec.branch(),
                spec.sha(),
                spec.image(),
                daemonId,
                credentials.secret(),
                spec.daemonBinaryUrl()));
    if (!launched.started()) {
      // Docker refused. No container exists, so there is no log to capture — what docker said is the
      // whole account, and "the step failed" would be a lie about a step that never ran.
      return failed(StepOutcome.LAUNCH_FAILED, launched.error());
    }

    if (!registry.awaitRegistered(daemonId, Duration.ofSeconds(registerTimeoutSeconds))) {
      // The container came up and nothing ever dialled. The bootstrap's own stderr is the diagnosis
      // — a missing downloader, an unfetchable binary url — and it is captured before the reap,
      // which is the whole reason these containers do not carry --rm.
      return failed(StepOutcome.NEVER_STARTED, launcher.logs(containerName));
    }

    CiDaemonRegistry.Initialization initialization =
        registry.awaitInitialized(daemonId, Duration.ofSeconds(initTimeoutSeconds));
    switch (initialization.status()) {
      case INITIALIZED -> {
        /* the step is the reply to this */
      }
      case INIT_FAILED -> {
        return failed(outcomeOf(initialization.reason()), detailOf(initialization));
      }
      case NEVER_INITIALIZED -> {
        return failed(StepOutcome.NEVER_INITIALIZED, launcher.logs(containerName));
      }
      case CONNECTION_LOST -> {
        return failed(StepOutcome.CONNECTION_LOST, launcher.logs(containerName));
      }
    }

    // The host initiates nothing toward a container: the step rides the reply to the daemon's own
    // Initialized, and this send is the step's started_at.
    listener.onStarted();
    registry.sendRunStep(daemonId, spec.script(), spec.timeoutSeconds());

    Duration backstop = Duration.ofSeconds(spec.timeoutSeconds() + stepTimeoutGraceSeconds);
    CiDaemonRegistry.Completion completion = registry.awaitFinished(daemonId, backstop);
    listener.onFinished();

    return switch (completion.status()) {
      case FINISHED ->
          new StepResult(
              completion.exitCode(), completion.timedOut(), StepOutcome.OK, tail(spec.runId()));
      case NO_ANSWER -> {
        // The daemon should have enforced the deadline itself and reported it; it did not, so the
        // host's backstop stands in. Ask the container to kill its child before removing it, so a
        // daemon that is merely slow still gets to end its own step cleanly.
        LOG.warnf(
            "ci-daemon %s did not answer within %s of its step's deadline — cancelling and reaping",
            daemonId, backstop);
        registry.cancel(daemonId);
        yield new StepResult(-1, true, StepOutcome.OK, tail(spec.runId()));
      }
      case CONNECTION_LOST ->
          new StepResult(-1, false, StepOutcome.CONNECTION_LOST, tail(spec.runId()));
    };
  }

  @Override
  public void cancel(String runId) {
    InFlight current = inFlight.get(runId);
    if (current == null) {
      return;
    }
    // A step that is already running dies gracefully: the daemon answers a Cancel with a terminal
    // frame, so the worker's await completes normally instead of burning its deadline on a socket
    // the host then has to reap anyway. Before that there is nothing to cancel — the container has
    // no step yet — so the launch is torn down instead, which completes the same await at once.
    if (registry.phaseOf(current.daemonId()) == CiDaemonRegistry.Phase.RUNNING) {
      registry.cancel(current.daemonId());
      return;
    }
    LOG.debugf("Cancelling run %s before its step started — reaping the container", runId);
    registry.reap(current.daemonId());
    launcher.reap(current.containerName());
  }

  @Override
  public void runClosed(String runId) {
    relay.drop(runId);
    inFlight.remove(runId);
  }

  /**
   * A step that never reached its script. {@code detail} is whatever account exists — docker's
   * refusal, the container's own log tail, the daemon's structured detail — and is recorded as data
   * about the run, never parsed for meaning.
   */
  private static StepResult failed(StepOutcome outcome, String detail) {
    return new StepResult(-1, false, outcome, detail == null ? "" : detail);
  }

  /**
   * Map a structured setup failure onto the outcome the orchestrator branches on.
   *
   * <p><b>A null reason is reachable and must stay harmless.</b> The codec decodes an
   * <em>absent</em> {@code reason} to null rather than throwing — deliberately, so a malformed
   * {@code InitFailed} still reaches the host as a recordable failure instead of vanishing into the
   * undecodable-frame log — and a hostile daemon can send exactly that frame. It is never
   * dereferenced here, and it never reaches {@link StepOutcome#SHA_GONE}, whose branch discards the
   * run: a container must not be able to delete the run that is watching it by omitting a field.
   */
  private static StepOutcome outcomeOf(InitFailed.Reason reason) {
    return reason == InitFailed.Reason.SHA_GONE ? StepOutcome.SHA_GONE : StepOutcome.INIT_FAILED;
  }

  private static String detailOf(CiDaemonRegistry.Initialization initialization) {
    String reason = initialization.reason() == null ? "unspecified" : initialization.reason().name();
    String detail = initialization.detail();
    return detail == null || detail.isBlank() ? reason : reason + ": " + detail;
  }

  /**
   * The step's output, read back out of the relay that accumulated it. One buffer, one budget: the
   * bound is a security property (a step's output is unbounded and attacker-controlled) and two
   * implementations of it drift into one that is not applied.
   */
  private String tail(String runId) {
    return relay.snapshot(runId).map(CiStepRelay.Snapshot::output).orElse("");
  }
}
