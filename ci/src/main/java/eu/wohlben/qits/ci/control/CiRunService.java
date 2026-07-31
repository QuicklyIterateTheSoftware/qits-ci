package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.control.CiStepRunner.DaemonPin;
import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

/**
 * The pipeline orchestrator: a post-receive event → read the config from the pushed commit → run
 * its steps sequentially → record per-step pass/fail. Runs execute on a single-threaded daemon
 * worker (the intake returns immediately; runs across all repos are serialized — parallelism is an
 * explicit follow-up), with each DB transition in its own {@link QuarkusTransaction#requiringNew()}
 * bracket so the slow container work never holds a transaction (worker threads have no request
 * context; the {@code BlobService}/{@code GitHostRoutes} stance).
 *
 * <p>That worker now parks on a socket rather than on a process — {@link CiStepRunner} waits for a
 * step container's own daemon — but the shape is unchanged: one blocking call per step, in order.
 *
 * <p><b>There are two ways in and one way through.</b> {@link #onPostReceive} is the push, which
 * names its own commit and has this class read the config out of it; {@link #onEventTrigger} is a
 * domain event that matched a {@code .config/qits/ci-event-*.yml}, which names no commit and arrives
 * with its pipeline already parsed at the head of {@code main} ({@code CiEventTriggerService} had to
 * read the file to know there was anything to run). From {@link #runSteps} down they are the same
 * path — same worker, same recording semantics, same containers — and what tells them apart
 * afterwards is the row's own provenance.
 *
 * <p><b>Steps are persisted at their end.</b> A {@link CiStep} row is inserted once, already
 * terminal; while a step runs it has no row at all and the live output is the runner's in-memory
 * relay, exposed on the run read surface as {@code live}. The never-run remainder is written {@code
 * SKIPPED} when the run closes. So the database never holds a half-written step and there is no
 * insert-then-update anywhere in a run.
 *
 * <p>Recording semantics — a run is only ever recorded when it says something true about a commit:
 *
 * <ul>
 *   <li>no config file ⇒ nothing (opt-in);
 *   <li>git host unreachable ⇒ nothing, warn-logged (a read failure must not invent a gate);
 *   <li>commit no longer reachable (force-pushed away) ⇒ nothing, including when the discovery
 *       happens later, in a step container's own checkout — the push it belonged to no longer
 *       exists, so a red run would blame a commit whose build was never broken;
 *   <li>config present but broken ⇒ {@link CiRunStatus#CONFIG_ERROR}, so the broken gate is
 *       visible;
 *   <li>config present with no steps ⇒ a trivially green run.
 * </ul>
 */
@ApplicationScoped
public class CiRunService {

  private static final Logger LOG = Logger.getLogger(CiRunService.class);

  /**
   * Prefixed onto an output tail whose head was dropped. Public because the runner applies the
   * budget incrementally, as output arrives, and must be able to say so with the same words — one
   * marker, one spelling.
   */
  public static final String TRUNCATION_MARKER = "[... output truncated ...]\n";

  @Inject CiConfigSource configSource;
  @Inject CiConfigParser parser;
  @Inject CiStepRunner runner;
  @Inject CiRunRepository runs;
  @Inject CiStepRepository steps;

  /** The green-run announcement port (see {@link CdNotifier}); zero implementations is fine. */
  @Inject Instance<CdNotifier> cdNotifiers;

  /** The green-run event port (see {@link RunAnnouncer}); zero implementations is fine. */
  @Inject Instance<RunAnnouncer> runAnnouncers;

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  /** The deadline a step gets when its declaration does not name one. */
  @ConfigProperty(name = "qits.ci.step-timeout-seconds")
  int stepTimeoutSeconds;

  /**
   * Runs a user asked to stop. In memory and deliberately so: a cancellation is only meaningful
   * while the run it addresses is executing in <em>this</em> process, and a restart fails every
   * in-flight run anyway.
   */
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ci-run-worker");
            t.setDaemon(true);
            return t;
          });

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  /**
   * A run left {@code RUNNING} by a crash or a kill can never make progress — the worker queue does
   * not survive the JVM — so it would show as forever-executing. Fail those once at startup.
   *
   * <p>The container half of the same reconciliation is {@code CiDaemonLauncher.onStart}, which
   * reaps what those runs left behind; it is a second observer because it needs docker and this
   * module has no business knowing about it.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      int swept =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<CiRun> orphans = runs.list("status = ?1", CiRunStatus.RUNNING);
                    for (CiRun orphan : orphans) {
                      failIncompleteSteps(orphan.id);
                      orphan.status = CiRunStatus.FAILED;
                      orphan.finishedAt = Instant.now();
                    }
                    return orphans.size();
                  });
      if (swept > 0) {
        LOG.infof("Marked %d CI run(s) left RUNNING by a previous shutdown as FAILED", swept);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted CI runs at startup");
    }
  }

  /** The async entry the event intake calls — returns immediately, the run executes queued. */
  public void onPostReceive(String repoId, String branch, String oldSha, String newSha) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);
    CiIdentifiers.requireSha(newSha);
    worker.submit(
        () -> {
          try {
            execute(repoId, branch, newSha);
          } catch (RuntimeException e) {
            LOG.errorf(e, "CI run for %s@%s (%s) failed unexpectedly", repoId, branch, newSha);
          }
        });
  }

  /**
   * One matched event trigger, resolved: the repository, the head the trigger was read at, the
   * pipeline that file declared, and the event that caused all of it.
   *
   * <p>The pipeline travels here <b>already parsed</b>, unlike the post-receive path which parses on
   * this worker. That is the shape the two triggers force: an event has no commit of its own, so
   * {@code CiEventTriggerService} must resolve the head and read the file to know whether there is
   * anything to run at all — by the time it knows, it has parsed. Re-reading here would be a second
   * fetch against a branch that may have moved, and the run would then record a sha it did not build.
   */
  public record EventRun(
      String repoId,
      String branch,
      String sha,
      CiEventTrigger trigger,
      String eventId,
      String eventName,
      Instant occurredAt,
      String payload) {}

  /** The async entry the trigger engine calls — returns immediately, the run executes queued. */
  public void onEventTrigger(EventRun request) {
    CiIdentifiers.requireRepoId(request.repoId());
    CiIdentifiers.requireBranch(request.branch());
    CiIdentifiers.requireSha(request.sha());
    worker.submit(
        () -> {
          try {
            executeEventRun(request);
          } catch (RuntimeException e) {
            LOG.errorf(
                e,
                "Event-triggered CI run for %s (%s) failed unexpectedly",
                request.repoId(),
                request.eventId());
          }
        });
  }

  /**
   * The synchronous event-triggered run — package-private so tests drive it without the worker.
   *
   * <p>It joins the post-receive path at {@link #runSteps}: same worker, same recording semantics,
   * same daemon pin, same step containers. The two differences are both in the row it writes — the
   * provenance columns, and the {@code QITS_EVENT_*} environment its containers get.
   *
   * <p><b>The insert is the dedupe.</b> A second arrival of the same event hits the unique constraint
   * on {@code (trigger_event_id, repo_id, config_path)} and is dropped as already-triggered rather
   * than re-run: {@link #persistEventRun} returns null and this returns without a run. There is no
   * "already triggered" state to record — the first run is the record.
   */
  void executeEventRun(EventRun request) {
    // Resolved once, here, exactly as on the push path: every step container of this run downloads
    // the same daemon build.
    DaemonPin pin = runner.pinDaemon();
    CiRun run = persistEventRun(request, pin.version());
    if (run == null) {
      return;
    }
    try {
      runSteps(run, request.trigger().pipeline(), pin, eventEnv(request));
    } catch (RuntimeException e) {
      LOG.errorf(e, "CI run %s failed unexpectedly", run.id);
      QuarkusTransaction.requiringNew().run(() -> failIncompleteSteps(run.id));
      finishRun(run.id, CiRunStatus.FAILED);
    } finally {
      cancelled.remove(run.id);
      runner.runClosed(run.id);
    }
  }

  /**
   * What an event-triggered run's step containers see, and the whole of it.
   *
   * <p>The payload goes in <b>verbatim</b>, as the canonical JSON qits-events stored — no per-field
   * flattening. Env names derived from payload paths invite collisions and quoting bugs, and {@code
   * jq} is already the platform's answer inside a step; a step that wants one field asks for it.
   */
  private static Map<String, String> eventEnv(EventRun request) {
    Map<String, String> env = new TreeMap<>();
    env.put("QITS_EVENT_ID", request.eventId());
    env.put("QITS_EVENT_NAME", request.eventName());
    env.put(
        "QITS_EVENT_OCCURRED_AT",
        request.occurredAt() == null ? "" : request.occurredAt().toString());
    env.put("QITS_EVENT_PAYLOAD", request.payload() == null ? "" : request.payload());
    return Map.copyOf(env);
  }

  /** The synchronous run — package-private so tests drive it without the worker. */
  void execute(String repoId, String branch, String sha) {
    ConfigLookup lookup = configSource.read(repoId, branch, sha);
    switch (lookup.status()) {
      case ABSENT -> {
        LOG.debugf("No %s at %s@%s — no CI run", CiConfigParser.CONFIG_PATH, repoId, sha);
        return;
      }
      case GONE -> {
        LOG.infof("Commit %s is no longer reachable in %s — no CI run recorded", sha, repoId);
        return;
      }
      case UNREACHABLE -> {
        LOG.warnf("Could not fetch %s from the git host — no CI run recorded for %s", sha, repoId);
        return;
      }
      case INVALID -> {
        LOG.infof("CI config unusable at %s@%s: %s", repoId, sha, lookup.message());
        persistPostReceiveRun(repoId, branch, sha, CiRunStatus.CONFIG_ERROR, null);
        return;
      }
      case FOUND -> {
        /* fall through to parse + run */
      }
    }

    CiPipeline pipeline;
    try {
      pipeline = parser.parse(lookup.content());
    } catch (CiConfigException e) {
      LOG.infof("CI config error at %s@%s: %s", repoId, sha, e.getMessage());
      persistPostReceiveRun(repoId, branch, sha, CiRunStatus.CONFIG_ERROR, null);
      return;
    }

    // Resolved once, here: every step container of this run downloads the same daemon build.
    DaemonPin pin = runner.pinDaemon();
    CiRun run = persistPostReceiveRun(repoId, branch, sha, CiRunStatus.RUNNING, pin.version());
    try {
      runSteps(run, pipeline, pin, Map.of());
    } catch (RuntimeException e) {
      LOG.errorf(e, "CI run %s failed unexpectedly", run.id);
      QuarkusTransaction.requiringNew().run(() -> failIncompleteSteps(run.id));
      finishRun(run.id, CiRunStatus.FAILED);
    } finally {
      cancelled.remove(run.id);
      runner.runClosed(run.id);
    }
  }

  /**
   * The sequential loop. Each iteration blocks on one container's whole lifetime and then writes
   * exactly one terminal row; whatever the loop did not reach is written {@code SKIPPED} at the end.
   */
  private void runSteps(
      CiRun run, CiPipeline pipeline, DaemonPin pin, Map<String, String> env) {
    List<CiPipeline.CiStepDecl> declared = pipeline.steps();
    int index = 0;
    boolean failed = false;

    try {
      while (index < declared.size() && !failed && !cancelled.contains(run.id)) {
        CiPipeline.CiStepDecl decl = declared.get(index);
        Stamps stamps = new Stamps();
        StepResult result =
            runner.run(
                new CiStepRunner.StepSpec(
                    run.id,
                    index,
                    run.repoId,
                    run.branch,
                    run.commitSha,
                    decl.image(),
                    decl.script(),
                    pin.binaryUrl(),
                    decl.timeoutSeconds() == null ? stepTimeoutSeconds : decl.timeoutSeconds(),
                    decl.docker(),
                    env),
                stamps);

        // A cancellation completes the await NORMALLY — the daemon answers a Cancel with a terminal
        // frame — so cancelledness is read from the flag rather than inferred from how run() came
        // back.
        boolean wasCancelled = cancelled.contains(run.id);

        // The daemon's checkout could not find the pushed sha. Two very different causes, so ask git
        // which it was rather than guessing: the commit may have been force-pushed away since the
        // config read (this run describes a push that no longer exists ⇒ discard it), or the repo
        // may still hold it and something else went wrong with the clone (a real, user-visible
        // failure that must stay on the record). The daemon's structured outcome is the probe now;
        // the re-read is the confirmation, exactly as it was behind the prelude sentinel.
        if (result.outcome() == StepOutcome.SHA_GONE && !wasCancelled) {
          boolean commitGone =
              configSource.read(run.repoId, run.branch, run.commitSha).status()
                  == ConfigLookup.Status.GONE;
          if (commitGone) {
            LOG.infof(
                "CI run %s: %s is no longer reachable — discarding the run", run.id, run.commitSha);
            discardRun(run.id);
            return;
          }
          LOG.infof(
              "CI run %s: step %d could not check out %s though the commit is still reachable: %s",
              run.id, index, run.commitSha, firstLine(result.output()));
        }

        boolean ok =
            !wasCancelled
                && !result.timedOut()
                && result.outcome() == StepOutcome.OK
                && result.exitCode() == 0;
        insertStep(
            run.id,
            index,
            decl.image(),
            ok ? CiStepStatus.SUCCESS : CiStepStatus.FAILED,
            result.exitCode(),
            annotate(result, wasCancelled),
            stamps.startedAt(),
            stamps.finishedAt());
        failed = !ok;
        index++;
      }
    } catch (RuntimeException e) {
      // The step blew up instead of answering — an infrastructure error, not a pipeline verdict.
      // Record it against the step it happened on so no declared step vanishes from the run.
      LOG.errorf(e, "CI run %s: step %d failed unexpectedly", run.id, index);
      if (index < declared.size()) {
        Instant now = Instant.now();
        insertStep(
            run.id,
            index,
            declared.get(index).image(),
            CiStepStatus.FAILED,
            null,
            "[the step could not be executed: " + e + "]",
            now,
            now);
        index++;
      }
      failed = true;
    }

    for (int skipped = index; skipped < declared.size(); skipped++) {
      insertStep(
          run.id,
          skipped,
          declared.get(skipped).image(),
          CiStepStatus.SKIPPED,
          null,
          null,
          null,
          null);
    }
    boolean red = failed || cancelled.contains(run.id);
    Instant finishedAt = finishRun(run.id, red ? CiRunStatus.FAILED : CiRunStatus.SUCCESS);
    if (!red) {
      notifyCd(run);
      announceRun(run, finishedAt);
    }
  }

  /**
   * Announces a green run through the {@link CdNotifier} port — after the terminal row is
   * committed, so a listener that reads the run back sees {@code SUCCESS}. Absent implementations
   * are a supported configuration (a deployment without qits-cd), and a throwing one must not turn
   * a green run red: the run <em>is</em> green, delivery is somebody else's outcome.
   */
  private void notifyCd(CiRun run) {
    for (CdNotifier notifier : cdNotifiers) {
      try {
        notifier.onRunSucceeded(run.id, run.repoId, run.branch, run.commitSha);
      } catch (RuntimeException e) {
        LOG.warnf(e, "CD notification for run %s failed", run.id);
      }
    }
  }

  /**
   * Announces a green run through the {@link RunAnnouncer} port — after the terminal row is
   * committed, for the same reason {@link #notifyCd} is, and carrying the {@code finishedAt} that
   * was just written rather than a fresh {@code Instant.now()}: the two are minutes apart in a slow
   * transition and the event log wants the one on the row.
   *
   * <p>{@code finishedAt} comes back from {@link #finishRun} instead of being read off {@code run}
   * because it is not there — {@link #finishRun} mutates a freshly loaded entity in its own
   * transaction, so this detached instance never sees the value. Reading it back would be a second
   * query for something this method already knows.
   *
   * <p>Failures are the port's, not the run's: a green run stays green whatever an announcement
   * does.
   *
   * <p><b>{@code triggerEventId} rides along, and it is how causation crosses a thread.</b> On an
   * event-triggered run it is the event that caused the run; the announcer hands it to the bus as the
   * published event's parent, so the run's own {@code BuildSuccessful} names what caused it and a
   * release train is a chain in the event log. It comes off the row rather than out of an ambient
   * context because there is none to read here: the engine consumed the frame on the bus's dispatch
   * thread and this is {@code ci-run-worker}, minutes later. Null on every push, which publishes a
   * root — correctly, since a push is not caused by an event.
   */
  private void announceRun(CiRun run, Instant finishedAt) {
    for (RunAnnouncer announcer : runAnnouncers) {
      try {
        announcer.onRunSucceeded(
            run.id, run.repoId, run.branch, run.commitSha, finishedAt, run.triggerEventId);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing run %s failed", run.id);
      }
    }
  }

  /**
   * The step's own tail plus one bracketed line naming anything that is not "the script ran and
   * exited". The runner's output is already bounded to {@code outputMaxChars} as it arrived; {@link
   * #tail} stays over it as the guard that keeps that a property of this class rather than a promise
   * made elsewhere.
   */
  private String annotate(StepResult result, boolean wasCancelled) {
    String output = tail(result.output(), outputMaxChars);
    String note = note(result, wasCancelled);
    if (note == null) {
      return output;
    }
    return (output == null || output.isEmpty() ? "" : output + "\n") + note;
  }

  private static String note(StepResult result, boolean wasCancelled) {
    if (wasCancelled) {
      return "[step cancelled]";
    }
    if (result.timedOut()) {
      return "[step timed out]";
    }
    return switch (result.outcome()) {
      case OK -> null;
      case SHA_GONE -> "[the step container could not check out this commit]";
      case INIT_FAILED -> "[the step container could not prepare its workspace]";
      case NEVER_INITIALIZED -> "[the step container never reported its checkout done]";
      case LAUNCH_FAILED -> "[the step container could not be started]";
      case NEVER_STARTED -> "[the step container never started its ci daemon]";
      case CONNECTION_LOST -> "[the connection to the step container was lost]";
    };
  }

  /** A run caused by a push: the constant config path, no event, {@code POST_RECEIVE}. */
  private CiRun persistPostReceiveRun(
      String repoId, String branch, String sha, CiRunStatus status, String daemonVersion) {
    CiRun run = newRun(repoId, branch, sha, status, daemonVersion);
    run.triggerType = CiTriggerType.POST_RECEIVE;
    run.configPath = CiConfigParser.CONFIG_PATH;
    // No step rows: they are written one at a time, terminal, as each step ends.
    QuarkusTransaction.requiringNew().run(() -> runs.persist(run));
    return run;
  }

  /**
   * A run caused by a domain event, or <b>null when this (event, repository, trigger file) already
   * has one</b>.
   *
   * <p>Both halves of that are here on purpose. The {@link CiRunRepository#alreadyTriggered} query is
   * the cheap one and catches the ordinary case — a redelivery, which the bus is allowed to do and
   * which a future catch-up feature will do deliberately. The caught constraint violation is the one
   * that matters: it is the guarantee, it holds across a race and a restart in a way no read-then-write
   * can, and reaching it is not an error to report but the answer to a question. Anything that is
   * <em>not</em> a unique violation is rethrown, because a run that failed to insert for some other
   * reason is a defect and must not look like a duplicate.
   *
   * <p>Both run in <b>one</b> {@code requiringNew} bracket, which they have to for two reasons: this
   * is the run worker and a worker thread has no request context, so an unwrapped read has no session
   * at all; and a check in its own transaction would be answering about a moment that has already
   * passed by the time the insert happens.
   */
  private CiRun persistEventRun(EventRun request, String daemonVersion) {
    String configPath = request.trigger().configPath();
    CiRun run =
        newRun(
            request.repoId(),
            request.branch(),
            request.sha(),
            CiRunStatus.RUNNING,
            daemonVersion);
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = configPath;
    run.triggerEventId = request.eventId();
    run.triggerEventName = request.eventName();
    boolean inserted;
    try {
      inserted =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    if (runs.alreadyTriggered(request.eventId(), request.repoId(), configPath)) {
                      return false;
                    }
                    runs.persist(run);
                    return true;
                  });
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) {
        throw e;
      }
      LOG.infof(
          "Event %s reached %s in %s twice — the first run stands",
          request.eventId(), configPath, request.repoId());
      return null;
    }
    if (!inserted) {
      LOG.debugf(
          "Event %s already triggered %s in %s — no second run",
          request.eventId(), configPath, request.repoId());
      return null;
    }
    return run;
  }

  /**
   * Whether a failed insert was the unique constraint rather than something else. Walked rather than
   * matched on a message: the exception a Panache persist wraps a constraint violation in depends on
   * the transaction boundary that flushed it, so the cause chain is the only stable place to look.
   */
  private static boolean isUniqueViolation(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException
          || cause instanceof SQLIntegrityConstraintViolationException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  private static CiRun newRun(
      String repoId, String branch, String sha, CiRunStatus status, String daemonVersion) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.branch = branch;
    run.commitSha = sha;
    run.status = status;
    run.createdAt = Instant.now();
    run.daemonVersion = daemonVersion;
    if (status != CiRunStatus.RUNNING) {
      run.finishedAt = run.createdAt;
    }
    return run;
  }

  /** Writes one step row. Every row this class writes is already in a terminal state. */
  private void insertStep(
      String runId,
      int stepIndex,
      String image,
      CiStepStatus status,
      Integer exitCode,
      String output,
      Instant startedAt,
      Instant finishedAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiStep step = new CiStep();
              step.id = UUID.randomUUID().toString();
              step.runId = runId;
              step.stepIndex = stepIndex;
              step.image = image;
              step.status = status;
              step.exitCode = exitCode;
              step.output = output;
              step.startedAt = startedAt;
              step.finishedAt = finishedAt;
              steps.persist(step);
            });
  }

  /**
   * Moves a run's non-terminal steps to terminal states (RUNNING ⇒ FAILED, PENDING ⇒ SKIPPED).
   *
   * <p>Nothing this class writes is ever non-terminal any more, so this only ever finds <b>legacy</b>
   * rows — steps persisted upfront by a version of this service that predates persist-at-finish. It
   * stays for exactly that, and for the startup sweep that is its only remaining caller of substance.
   */
  private void failIncompleteSteps(String runId) {
    for (CiStep step : steps.listByRunIdOrdered(runId)) {
      if (step.status == CiStepStatus.RUNNING) {
        step.status = CiStepStatus.FAILED;
      } else if (step.status == CiStepStatus.PENDING) {
        step.status = CiStepStatus.SKIPPED;
      }
    }
  }

  /**
   * Writes the run's terminal row and hands back the instant it stamped, which is the one thing
   * about a finished run that is not already in the caller's hand — {@link RunAnnouncer} needs it,
   * and taking it from the transaction that wrote it is what keeps the row and the event agreeing
   * on when the run ended.
   */
  private Instant finishRun(String runId, CiRunStatus status) {
    Instant finishedAt = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(runId);
              run.status = status;
              run.finishedAt = finishedAt;
            });
    return finishedAt;
  }

  /** Removes a run that turned out to describe a commit that no longer exists. */
  private void discardRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.delete("runId = ?1", runId);
              runs.deleteById(runId);
            });
  }

  /**
   * Stop a running run: flag it and ask its in-flight step's container to die. Returns as soon as
   * both are done, which is well before the run is actually finished — the caller answers 202.
   *
   * <p>Cancelling anything that is not {@code RUNNING} is a 409 rather than a quiet success: a
   * finished run has nothing to stop, and telling the caller it does would be a lie it cannot check.
   */
  public void cancel(String runId) {
    CiRun run = requireRun(runId);
    if (run.status != CiRunStatus.RUNNING) {
      throw new ConflictException(
          "CI run " + runId + " is not running (" + run.status + ") — nothing to cancel");
    }
    cancelled.add(runId);
    runner.cancel(runId);
    LOG.infof("CI run %s cancelled on request", runId);
  }

  /** All runs recorded for a repository, newest-first. */
  public List<CiRun> runsFor(String repoId) {
    return runs.listByRepoIdNewestFirst(repoId);
  }

  /**
   * The newest {@code limit} runs recorded for a repository, or all of them when {@code limit} is
   * null.
   *
   * @throws BadRequestException if a limit is given and is not positive — zero rows is a question
   *     nobody asks, and a negative bound is a caller bug rather than an empty answer
   */
  public List<CiRun> runsFor(String repoId, Integer limit) {
    if (limit == null) {
      return runsFor(repoId);
    }
    if (limit <= 0) {
      throw new BadRequestException("Invalid limit");
    }
    return runs.listByRepoIdNewestFirst(repoId, limit);
  }

  /**
   * Every repository this instance has recorded a run for, ascending — the read surface behind
   * {@code GET /ci/api/repositories}.
   *
   * <p>Sorted here rather than left to the caller so the response is stable across calls and across
   * instances: a client diffing "which repositories have CI activity" against the projects registry
   * must not see the set reorder because the query planner did.
   *
   * <p>These are ids qits-ci <b>observed</b> on its own runs, not repositories it owns. It is
   * deliberately narrower than {@link CiCandidateRepos#candidates()}, which also counts the bare
   * caches on disk: a repository ci once fetched for but never recorded a run against has no CI
   * history to explore, and listing it here would promise one.
   */
  public List<String> repositoryIds() {
    return runs.distinctRepoIds().stream().sorted().toList();
  }

  /** The run, or 404. */
  public CiRun requireRun(String runId) {
    return runs.findByIdOptional(runId)
        .orElseThrow(() -> new NotFoundException("No such CI run: " + runId));
  }

  /** A run's steps in declaration order. */
  public List<CiStep> stepsFor(String runId) {
    return steps.listByRunIdOrdered(runId);
  }

  /** Keeps the LAST {@code maxChars} chars (a step's tail is where the failure is), marked. */
  public static String tail(String output, int maxChars) {
    if (output == null || output.length() <= maxChars) {
      return output;
    }
    return TRUNCATION_MARKER + output.substring(output.length() - maxChars);
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no output)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /**
   * The listener a step is run with: it exists to take the two host-side timestamps at the moments
   * the plan pins them to — {@code RunStep} sent, terminal frame received — rather than around the
   * blocking call, which would fold an image pull and a clone into "the step started".
   *
   * <p>Chunks are ignored here on purpose. The live surface is the runner's own relay, which lives
   * beside the socket the chunks arrive on; this module has no business holding a second copy of an
   * unbounded stream. Both fall back to a sane instant so a step that failed before it ever started
   * still gets an honest row.
   */
  private static final class Stamps implements CiStepRunner.StepListener {

    private final Instant began = Instant.now();
    private volatile Instant started;
    private volatile Instant finished;

    @Override
    public void onStarted() {
      started = Instant.now();
    }

    @Override
    public void onChunk(String text) {
      // The relay is the live surface; the row carries the tail the runner accumulated.
    }

    @Override
    public void onFinished() {
      finished = Instant.now();
    }

    Instant startedAt() {
      return started != null ? started : began;
    }

    Instant finishedAt() {
      return finished != null ? finished : Instant.now();
    }
  }

  /** Test hook: waits for the work queued at this moment to drain. */
  void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
