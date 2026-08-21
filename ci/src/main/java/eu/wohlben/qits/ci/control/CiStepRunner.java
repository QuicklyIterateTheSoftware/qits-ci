package eu.wohlben.qits.ci.control;

import java.util.Map;

/**
 * Executes one pipeline step. The sole implementation is {@code CiDaemonStepRunner} in the service
 * module: it starts a container, waits for that container's own {@code qits-ci-daemon} to dial back,
 * and hands the step's script over as the reply to the daemon's {@code Initialized}. <b>Nothing on
 * this side of the seam ever runs a step's script</b> — see the invariant in {@code CLAUDE.md}.
 *
 * <p>The seam survived the swap as a name and as a <em>shape</em>: still exactly one blocking call
 * per step, so {@code CiRunService.runSteps} stays a sequential loop with one transaction per row
 * and each run worker remains sequential within one pipeline. What changed is that a step now <b>emits events</b>
 * while it runs — chunks as the container produces them, and the two lifecycle instants the host
 * stamps — instead of only answering once at the end.
 *
 * <p>Tests replace this bean with a scripted-event fake ({@code @io.quarkus.test.Mock}) so the
 * suites stay docker-free. Both fakes are scripted: no fake anywhere performs real step semantics,
 * which is what keeps {@code bash -c <repo content>} out of this repository entirely.
 */
public interface CiStepRunner {

  /**
   * Everything one step execution needs — ids and strings only, never entities.
   *
   * <p>{@code repo} is how the step addresses the repository it builds: the storage id it has always
   * carried, plus the public {@code (projectId, name)} pair when the run's announcing push carried
   * one. The implementation turns it into the clone URL and into the step's own {@code
   * QITS_CI_PROJECT_ID}/{@code QITS_CI_REPO_NAME}; with no pair it builds the id-addressed URL this
   * service always built.
   *
   * <p>{@code daemonBinaryUrl} is resolved once per run from {@link #pinDaemon()} and repeated into
   * every one of that run's containers, so a deploy landing mid-run cannot make step 3 speak a
   * different protocol than step 1. {@code timeoutSeconds} is the step's own deadline — the
   * per-step {@code timeout-seconds} from the pipeline config, or the {@code
   * qits.ci.step-timeout-seconds} default — enforced by the daemon inside the container, with the
   * host holding a longer backstop behind it.
   *
   * <p>{@code docker} is the step's own {@code docker: true} declaration, carried across the seam
   * untouched: this side neither interprets it nor defaults it, and the implementation turns it into
   * a bind mount of the host's docker socket. It travels here rather than being read from config
   * because it is a <em>property of the step</em> — one step of a run may have it and the next may
   * not, and the run row records each of them the same way.
   *
   * <p>{@code user} is the step's own {@code user:} declaration, carried across untouched like
   * {@code docker}. Empty means the config named nobody and the image's default stands. The
   * implementation turns it into the container's {@code --user}, which is the only place it can be
   * turned into anything: a step container runs {@code --cap-drop=ALL} and can neither {@code su}
   * nor {@code chown} from the inside.
   *
   * <p>{@code env} is <b>run-scoped</b> environment the container gets on top of the fixed contract:
   * today exactly the {@code QITS_EVENT_*} four an event-triggered run carries, and empty on every
   * push. It is a map rather than four fields because what it holds is a property of the <em>trigger</em>
   * and this seam has no business enumerating triggers; it is emitted in sorted key order so an argv
   * stays something a test can assert literally. <b>It never carries anything a repository authored</b>
   * — the implementation writes the platform's own variables after these, so nothing here can shadow
   * the contract the daemon boots on.
   */
  record StepSpec(
      String runId,
      int stepIndex,
      CiRepoRef repo,
      String branch,
      String sha,
      String image,
      String script,
      String daemonBinaryUrl,
      int timeoutSeconds,
      boolean docker,
      String user,
      Map<String, String> env) {}

  /**
   * How the step ended, in the vocabulary the orchestrator branches on. Every value here is a
   * <b>distinguishable</b> state rather than a shade of "the step failed" — a run whose container
   * docker refused, one whose container never started a daemon, one whose daemon never finished a
   * checkout, one whose checkout found no such commit, and one whose socket dropped mid-step are
   * five different things, and recording them as one exit code −1 is the thing the plan's
   * failure-state rule forbids.
   *
   * <p>Only {@link #SHA_GONE} carries a semantic beyond the recorded message: it is the daemon's
   * checkout reporting the pushed commit is not in the clone, which is how a force-push between the
   * host's ancestor check and the container's clone surfaces. The orchestrator confirms it against
   * the config source before discarding the run.
   */
  enum StepOutcome {
    /** The step ran. {@code exitCode} and {@code timedOut} are the step's own. */
    OK,
    /** The daemon cloned but could not check the pushed sha out — the force-push backstop. */
    SHA_GONE,
    /** The daemon reported a structured setup failure that was not a missing commit. */
    INIT_FAILED,
    /** The daemon registered and then said nothing before the initialize deadline. */
    NEVER_INITIALIZED,
    /** Docker refused to start the container at all; no daemon was ever possible. */
    LAUNCH_FAILED,
    /** The container started and nothing ever dialled back — the bootstrap's own log says why. */
    NEVER_STARTED,
    /** The control socket went away before a terminal frame. */
    CONNECTION_LOST
  }

  /**
   * What one step execution produced: the exit code, whether a deadline killed it, which of the
   * lifecycle states it reached, and its bounded output tail.
   *
   * <p>{@code timedOut} is a field rather than an inference from {@code exitCode} because a killed
   * child reports the kill's code (143, or 137 after the grace), which a script that traps a signal
   * can produce on its own. A timeout is recorded as a timeout.
   *
   * <p>{@code output} is <b>already bounded</b> to {@code qits.ci.output-max-chars} — it is
   * accumulated incrementally as chunks arrive, never assembled whole, because a step's output is
   * attacker-controlled and unbounded.
   */
  record StepResult(int exitCode, boolean timedOut, StepOutcome outcome, String output) {}

  /**
   * The step's live events, called on whatever thread the transport delivers them on — so an
   * implementation must not block for long and must not throw for anything it cares about.
   *
   * <p>The two instants are the host's, not the container's: a daemon is hostile from the moment
   * step code runs in it and a clock claim is the cheapest thing to forge, so {@code started_at} and
   * {@code finished_at} are stamped here, at the two points the host knows about first-hand.
   */
  interface StepListener {

    /** The host has sent {@code RunStep} — the step's {@code started_at}. */
    void onStarted();

    /** One chunk of combined output, in the order it arrived. */
    void onChunk(String text);

    /** A terminal frame arrived or a deadline fired — the step's {@code finished_at}. */
    void onFinished();
  }

  /**
   * The daemon build a run pins itself to: the version recorded on the run row, and the download url
   * every one of its step containers is handed. Resolved <b>once</b> per run, for the reason the
   * whole pin exists.
   */
  record DaemonPin(String version, String binaryUrl) {}

  /** Resolve the daemon pin for a new run. */
  DaemonPin pinDaemon();

  /** Run one step to completion, feeding {@code listener} as it goes. Blocks. */
  StepResult run(StepSpec spec, StepListener listener);

  /**
   * Ask this run's in-flight step to stop. Best-effort and asynchronous: the in-flight {@link #run}
   * returns of its own accord shortly after (a cancelled step still <em>finishes</em> — the daemon
   * answers with a terminal frame), so cancelledness is the caller's to record from its own flag and
   * is not inferrable from how the call came back. A no-op when nothing is in flight.
   */
  void cancel(String runId);

  /** The run reached a terminal state: release everything still held for it. */
  void runClosed(String runId);
}
