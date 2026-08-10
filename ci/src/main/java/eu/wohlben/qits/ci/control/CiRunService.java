package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.exception.ConstraintViolationException;
import org.jboss.logging.Logger;

/**
 * The pipeline orchestrator: a post-receive event → read the config from the pushed commit → run
 * its steps sequentially → record per-step pass/fail. Runs execute on a bounded pool of daemon
 * workers (the intake returns immediately; {@code qits.ci.concurrent-builds} controls how many runs
 * may execute at once), with each DB transition in its own {@link QuarkusTransaction#requiringNew()}
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
 * <h2>The run row is born at accept, not at start</h2>
 *
 * <p><b>Both entries insert a {@link CiRunStatus#QUEUED} row before they return</b>, and the worker
 * flips it to {@code RUNNING} when it dequeues it. Before that, a queued run was a closure on this
 * class's executor and nothing else — invisible to every read surface, and gone with the process.
 * That was the lossy intake: a redeploy landing between the push and the build lost the build with
 * no row anywhere to say so, and the remedy was to replay the post-receive by hand.
 *
 * <p>So the recording rule is <b>revised, deliberately</b>. It used to read "a run is only ever
 * recorded when it says something true about a commit", which was a statement about when the INSERT
 * happens. It now reads: <b>a run row exists from the moment the work is accepted, and it is removed
 * again if it turns out to describe nothing that happened.</b> What a <em>finished</em> worker
 * leaves behind is unchanged, outcome for outcome — the difference is a transient {@code QUEUED} row
 * in between, visible to {@code GET /ci/api/runs/active} and, briefly, to a repository's listing.
 *
 * <p>Recording semantics, per outcome, with what became of the accept-time row:
 *
 * <ul>
 *   <li>no config file ⇒ the row is <b>discarded</b> (opt-in: a repository that declares no pipeline
 *       must not accumulate a row per push);
 *   <li>git host unreachable ⇒ <b>discarded</b>, warn-logged (a read failure must not invent a gate,
 *       and inventing one is exactly what leaving a red row behind would be);
 *   <li>the repository no longer holds the commit ⇒ <b>discarded</b>, including when the discovery
 *       happens later, in a step container's own checkout — the push it belonged to no longer
 *       exists, so a red run would blame a commit whose build was never broken;
 *   <li>config present but broken ⇒ {@link CiRunStatus#CONFIG_ERROR}, so the broken gate is
 *       visible;
 *   <li>config present with no steps ⇒ a trivially green run;
 *   <li>cancelled while still {@code QUEUED} ⇒ {@code FAILED}, with no steps — a cancelled run has
 *       always been {@code FAILED} and a queue does not need a sixth status to say so.
 * </ul>
 *
 * <h2>What a restart costs now</h2>
 *
 * <p>{@link #sweepInterrupted} fails a push-triggered {@code RUNNING} row — its in-flight step died
 * with the process and replaying repository-authored work after it began is not safe. A running
 * event pipeline is different: the event bus is at-most-once and the complete input snapshot is on
 * the row, so its partial step records are cleared and the run is restarted. Event pipelines must
 * therefore be idempotent. Every {@code QUEUED} row is re-enqueued oldest first.
 *
 * <p>An event-triggered row also stores its event envelope and exact trigger-file content. The
 * worker reparses that immutable snapshot, so a queued event run is recoverable without consulting
 * a branch that may have moved and without relying on an at-most-once event redelivery.
 */
@ApplicationScoped
public class CiRunService {

  private static final Logger LOG = Logger.getLogger(CiRunService.class);

  /**
   * Boot order, second half. This observer runs <b>after</b> {@code CiDaemonLauncher.onStart}, whose
   * matching {@code @Priority} is one step lower. <b>Move neither alone</b> — see {@link #onStart}
   * for what the order buys.
   */
  public static final int BOOT_SWEEP_PRIORITY = 2100;

  /**
   * Prefixed onto an output tail whose head was dropped. Public because the runner applies the
   * budget incrementally, as output arrives, and must be able to say so with the same words — one
   * marker, one spelling.
   */
  public static final String TRUNCATION_MARKER = "[... output truncated ...]\n";

  /**
   * The branch a repository summary reports separately from its newest run. The platform's one
   * tracked branch — the same convention {@code CiEventTriggerService.TRIGGER_BRANCH} names, and
   * spelled here rather than imported from it because these are two independent facts that happen to
   * agree: one is where an event trigger reads, the other is what "is this repository green" means.
   */
  public static final String MAIN_BRANCH = "main";

  @Inject CiConfigSource configSource;
  @Inject CiConfigParser parser;
  @Inject CiEventTriggerParser triggerParser;
  @Inject CiStepRunner runner;
  @Inject CiRunRepository runs;
  @Inject CiStepRepository steps;

  /** The green-run event port (see {@link RunAnnouncer}); zero implementations is fine. */
  @Inject Instance<RunAnnouncer> runAnnouncers;

  /** The published-artifact port (see {@link ReleaseAnnouncer}); zero implementations is fine. */
  @Inject Instance<ReleaseAnnouncer> releaseAnnouncers;

  /**
   * The field a release pipeline's version comes out of. It is the triggering event's payload, read
   * by name — {@code SCMRelease} carries it, and a trigger file declaring artifacts against an event
   * that does not was written for something this cannot feed.
   */
  private static final String VERSION_FIELD = "version";

  public static final String USER_CANCELLED = "USER_CANCELLED";
  public static final String DEDUPED = "DEDUPED";
  public static final int MAX_CANCELLATION_REASON_LENGTH = 255;

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  /** The deadline a step gets when its declaration does not name one. */
  @ConfigProperty(name = "qits.ci.step-timeout-seconds")
  int stepTimeoutSeconds;

  /** The instance-wide upper bound on runs executing at the same time. */
  @ConfigProperty(name = "qits.ci.concurrent-builds")
  int concurrentBuilds;

  /**
   * Runs a user asked to stop. In memory and deliberately so: a cancellation is only meaningful
   * while the run it addresses is executing in <em>this</em> process, and a restart fails every
   * in-flight run anyway.
   */
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  private ExecutorService worker;

  @PostConstruct
  void initializeWorkers() {
    worker = createWorkerPool(concurrentBuilds);
  }

  static ExecutorService createWorkerPool(int concurrentBuilds) {
    if (concurrentBuilds < 1) {
      throw new IllegalArgumentException("qits.ci.concurrent-builds must be at least 1");
    }
    AtomicInteger workerNumber = new AtomicInteger();
    return Executors.newFixedThreadPool(
        concurrentBuilds,
        r -> {
          Thread t = new Thread(r, "ci-run-worker-" + workerNumber.incrementAndGet());
          t.setDaemon(true);
          return t;
        });
  }

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  /**
   * Reconciles what a previous process left behind, and the two halves pull in opposite directions
   * on purpose.
   *
   * <p>A push run left {@code RUNNING} cannot make progress and is failed once here. An event run
   * left running is reset and restarted from its durable snapshot because its at-most-once source
   * cannot redeliver it; trigger scripts are consequently an at-least-once/idempotent boundary.
   *
   * <p>A run left {@code QUEUED} never started. Its row is the whole of it, so it is <b>put back on
   * the worker</b> in {@code createdAt} order rather than failed: this is the point of the status
   * existing, and it is what closes the cutover loss for builds that were accepted while qits-ci was
   * redeploying itself. Event-triggered rows contain their envelope and trigger snapshot too, so
   * both trigger types take the same recovery path.
   *
   * <p>The container half of the same reconciliation is {@code CiDaemonLauncher.onStart}, which
   * reaps what the {@code RUNNING} runs left behind; it is a second observer because it needs docker
   * and this module has no business knowing about it.
   *
   * <p><b>That half runs first, and the {@code @Priority} pair is what says so.</b> This one does
   * not only write rows: it hands work straight back to the run worker, which starts labelled
   * containers of its own. The reap filters on the label alone and cannot tell a container this boot
   * just started from one the previous life left, so running it second would let it remove a
   * restarted run's first container. {@code CiDaemonLauncher.onStart} therefore carries the lower
   * priority and this one the higher; <b>neither moves alone</b>, and {@code
   * BootReconciliationOrderTest} holds the pair.
   */
  void onStart(@Observes @Priority(BOOT_SWEEP_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    sweepInterrupted();
  }

  /** What one sweep found: work to restart and counts worth exposing in the startup log. */
  private record Sweep(List<String> requeue, int failed, int restartedEvents) {}

  /**
   * The sweep itself — package-private because {@link #onStart} skips test mode, so this is what a
   * suite drives to make a claim about a restart.
   *
   * <p>The re-enqueue happens <b>after</b> the transaction commits: the worker's first act on a run
   * is to read its row back and check it is still {@code QUEUED}, which it cannot do against a write
   * this thread has not committed yet.
   */
  void sweepInterrupted() {
    Sweep sweep;
    try {
      sweep = QuarkusTransaction.requiringNew().call(this::reconcile);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted CI runs at startup");
      return;
    }
    if (sweep.failed() > 0) {
      LOG.infof(
          "Marked %d CI run(s) left RUNNING by a previous shutdown as FAILED", sweep.failed());
    }
    if (sweep.restartedEvents() > 0) {
      LOG.infof(
          "Restarting %d event-triggered CI run(s) interrupted by the previous shutdown",
          sweep.restartedEvents());
    }
    if (!sweep.requeue().isEmpty()) {
      LOG.infof("Re-enqueued %d CI run(s) left QUEUED by a previous shutdown", sweep.requeue().size());
      sweep.requeue().forEach(this::enqueue);
    }
  }

  private Sweep reconcile() {
    List<CiRun> orphans = runs.list("status = ?1", CiRunStatus.RUNNING);
    int failed = 0;
    int restartedEvents = 0;
    for (CiRun orphan : orphans) {
      if (orphan.triggerType == CiTriggerType.EVENT && orphan.triggerConfig != null) {
        steps.delete("runId = ?1", orphan.id);
        orphan.status = CiRunStatus.QUEUED;
        orphan.finishedAt = null;
        orphan.daemonVersion = null;
        restartedEvents++;
      } else {
        failIncompleteSteps(orphan.id);
        orphan.status = CiRunStatus.FAILED;
        orphan.finishedAt = Instant.now();
        failed++;
      }
    }

    List<String> requeue = new ArrayList<>();
    for (CiRun queued : runs.listQueuedOldestFirst()) {
      requeue.add(queued.id);
    }
    return new Sweep(requeue, failed, restartedEvents);
  }

  /**
   * The async entry a push takes. <b>The row is written before this returns</b> — the caller is the
   * bus adapter holding an event's claiming transaction open for it, so a settled event means "this
   * run is on the record and will be attempted", not "a closure exists somewhere".
   *
   * <p>The name outlived its transport on purpose: this is still what a post-receive <em>is</em> —
   * a branch moved to a commit — and the row it writes is still {@code POST_RECEIVE}. What used to
   * be an HTTP intake is {@code bus/ScmPublishCommitListener}, and the suppression the git host used
   * to apply for its consumers ({@code -o qits.no-ci}) is decided there, before this is called.
   *
   * <p><b>{@code eventId} is the {@code SCMPublishCommit} that announced the push</b>, and it lands
   * on the row's {@code triggerEventId} exactly as an event trigger's frame id does. That is what
   * closes the platform's causation chain across this hop: {@code announceRun} reads it back off the
   * row minutes later, on another thread, and stamps the run's {@code BuildSuccessful} with it — so
   * release → push → commit event → CI run → deploy is one chain rather than two halves with a root
   * event in the middle. It also puts push runs inside the unique constraint that used to see only
   * event runs, which is a second guarantee and not a cost: see {@link #acceptPostReceive}.
   *
   * <p>Null is accepted and means "no event announced this", which is the {@link #execute} test
   * entry and nothing in production. Such a run publishes a root event, which is the truthful
   * answer for a push nobody can name a cause for.
   */
  public void onPostReceive(
      String repoId, String branch, String oldSha, String newSha, String eventId) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);
    CiIdentifiers.requireSha(newSha);
    CiRun accepted = acceptPostReceive(repoId, branch, newSha, eventId);
    if (accepted != null) {
      enqueue(accepted.id);
    }
  }

  /** Puts an already-accepted (QUEUED) run on the worker. */
  private void enqueue(String runId) {
    worker.submit(
        () -> {
          try {
            CiRun queued = QuarkusTransaction.requiringNew().call(() -> runs.findById(runId));
            if (queued != null && queued.triggerType == CiTriggerType.EVENT) {
              runQueuedEventRun(runId, reconstructEventRun(queued));
            } else {
              runQueued(runId);
            }
          } catch (RuntimeException e) {
            LOG.errorf(e, "CI run %s failed unexpectedly", runId);
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
      String payload,
      String triggerConfig) {}

  /**
   * The async entry the trigger engine calls. Like the intake's, the row is written before this
   * returns — which moves the <b>dedupe</b> to accept time along with it.
   *
   * <p>That relocation is the whole of what changed for this path, and it changed nothing about the
   * semantics: a redelivered event still hits the unique constraint on {@code (trigger_event_id,
   * repo_id, config_path)} and is still dropped as already-triggered rather than re-run, just on the
   * trigger worker rather than on the run worker, and before a queue slot is spent rather than after.
   * A duplicate frame therefore never reaches the queue at all.
   *
   * @return the id of the run this recorded, or {@code null} when the dedupe dropped it. The manual
   *     trigger endpoint answers with these ids, which is what lets a 2xx there mean "these rows
   *     exist" rather than "something was handed to a queue".
   */
  public String onEventTrigger(EventRun request) {
    CiIdentifiers.requireRepoId(request.repoId());
    CiIdentifiers.requireBranch(request.branch());
    CiIdentifiers.requireSha(request.sha());
    CiRun run = acceptEventRun(request);
    if (run == null) {
      return null;
    }
    enqueue(run.id);
    return run.id;
  }

  /** Rebuilds an accepted event run solely from its durable row. */
  private EventRun reconstructEventRun(CiRun run) {
    CiEventTrigger trigger = triggerParser.parse(run.configPath, run.triggerConfig);
    return new EventRun(
        run.repoId,
        run.branch,
        run.commitSha,
        trigger,
        run.triggerEventId,
        run.triggerEventName,
        run.triggerEventOccurredAt,
        run.triggerEventPayload,
        run.triggerConfig);
  }

  /**
   * The synchronous event-triggered run — package-private so tests drive it without either worker.
   *
   * <p>It joins the post-receive path at {@link #runSteps}: same worker, same recording semantics,
   * same daemon pin, same step containers. The two differences are both in the row it writes — the
   * provenance columns, and the {@code QITS_EVENT_*} environment its containers get.
   */
  void executeEventRun(EventRun request) {
    CiRun run = acceptEventRun(request);
    if (run == null) {
      return;
    }
    runQueuedEventRun(run.id, request);
  }

  /**
   * The worker half of an event-triggered run: claim the queued row, then run the pipeline that
   * reconstructed from the durable row.
   *
   * <p>No config lookup, unlike the push path — {@code CiEventTriggerService} had to read and parse
   * the trigger file to know there was anything to run at all, so the pipeline arrives parsed. That
   * The row stores that file's exact content so a restart parses the same declaration again.
   */
  private void runQueuedEventRun(String runId, EventRun request) {
    CiRun run = startQueued(runId);
    if (run == null) {
      cancelled.remove(runId);
      return;
    }
    // Resolved once, here, exactly as on the push path: every step container of this run downloads
    // the same daemon build.
    DaemonPin pin = runner.pinDaemon();
    pinDaemonVersion(run.id, pin.version());
    run.daemonVersion = pin.version();
    try {
      runSteps(run, request.trigger().pipeline(), pin, eventEnv(request), declaredRelease(request));
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

  /**
   * What a green run of this trigger file will announce beyond {@code BuildSuccessful}: the
   * artifacts it declared, and the payload the version is read out of. Null when the file declared
   * none, which is every ordinary event pipeline and every push.
   *
   * <p>The payload is reconstructed from the run row and travels through to {@link #runSteps}.
   * Reading it here rather than at accept time remains deliberate: a red run must announce nothing
   * and warn about nothing.
   */
  private static DeclaredRelease declaredRelease(EventRun request) {
    List<CiArtifact> artifacts = request.trigger().artifacts();
    return artifacts.isEmpty() ? null : new DeclaredRelease(artifacts, request.payload());
  }

  /** The artifacts a run's trigger file declared, and the event payload they take their version from. */
  private record DeclaredRelease(List<CiArtifact> artifacts, String eventPayload) {}

  /**
   * Accept and run a push in one call — package-private so tests drive the whole state machine
   * without the worker's timing. No announcing event, so the run publishes a root.
   */
  void execute(String repoId, String branch, String sha) {
    runQueued(acceptPostReceive(repoId, branch, sha, null).id);
  }

  /**
   * The worker half of a push-triggered run: claim the queued row, then read the config out of the
   * commit and run what it declares.
   *
   * <p>Everything the run needs comes off the row, which is what makes this callable from the
   * startup sweep with nothing but an id.
   */
  void runQueued(String runId) {
    CiRun run = startQueued(runId);
    if (run == null) {
      // Somebody reached the row first — today that is only a cancellation of a still-queued run,
      // which has already written the terminal row. Nothing to run and nothing to clean up but the
      // flag that cancellation raced us with.
      cancelled.remove(runId);
      return;
    }
    try {
      executeQueued(run);
    } finally {
      cancelled.remove(run.id);
    }
  }

  /** Runs one claimed row. */
  private void executeQueued(CiRun run) {
    String repoId = run.repoId;
    String branch = run.branch;
    String sha = run.commitSha;

    ConfigLookup lookup = configSource.read(repoId, branch, sha);
    switch (lookup.status()) {
      case ABSENT -> {
        // Opt-in: the repository declares no pipeline for this push, so it gets no row. The row
        // that exists was written before anyone could know that, and discarding it is what keeps
        // "no config file, no run" true of the record.
        LOG.debugf("No %s at %s@%s — no CI run", CiConfigParser.CONFIG_PATH, repoId, sha);
        discardRun(run.id);
        return;
      }
      case GONE -> {
        LOG.infof("%s no longer holds commit %s — no CI run recorded", repoId, sha);
        discardRun(run.id);
        return;
      }
      case UNREACHABLE -> {
        // A read failure must not invent a gate, and leaving a red row behind is precisely that:
        // the commit is very likely fine and this process simply could not ask.
        LOG.warnf("Could not fetch %s from the git host — no CI run recorded for %s", sha, repoId);
        discardRun(run.id);
        return;
      }
      case INVALID -> {
        LOG.infof("CI config unusable at %s@%s: %s", repoId, sha, lookup.message());
        finishRun(run.id, CiRunStatus.CONFIG_ERROR);
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
      finishRun(run.id, CiRunStatus.CONFIG_ERROR);
      return;
    }

    // Resolved once, here: every step container of this run downloads the same daemon build. It is
    // written to the row now rather than at accept because a run that never launches a container —
    // a CONFIG_ERROR, a discarded one — pins no daemon and must keep saying so.
    DaemonPin pin = runner.pinDaemon();
    pinDaemonVersion(run.id, pin.version());
    run.daemonVersion = pin.version();
    try {
      runSteps(run, pipeline, pin, Map.of(), null);
    } catch (RuntimeException e) {
      LOG.errorf(e, "CI run %s failed unexpectedly", run.id);
      QuarkusTransaction.requiringNew().run(() -> failIncompleteSteps(run.id));
      finishRun(run.id, CiRunStatus.FAILED);
    } finally {
      runner.runClosed(run.id);
    }
  }

  /**
   * Prefixed onto the output of a step the run's branch did not bind. Public because it is the
   * documented form of the row — the explorer shows it, {@code AGENTS.md} and {@code README.md}
   * quote it, and a test reads it back.
   */
  public static final String NOT_BOUND_NOTE_PREFIX = "[step not bound to branch ";

  /** The whole note, for the branch a run is on. */
  public static String notBoundNote(String branch) {
    return NOT_BOUND_NOTE_PREFIX + branch + "]";
  }

  /**
   * The sequential loop. Each iteration blocks on one container's whole lifetime and then writes
   * exactly one terminal row; whatever the loop did not reach is written {@code SKIPPED} at the end.
   *
   * <p><b>A step the run's branch does not bind is written {@code SKIPPED} before any container
   * exists, and nothing else moves</b>: {@code failed} is untouched, the loop continues, and later
   * steps run. It launches nothing and is a non-event to the run's verdict — precisely unlike a
   * {@code FAILED} step, whose {@code failed = !ok} is what stops the loop and turns the run red. So
   * a run whose every step is branch-skipped finishes green, which is the existing "config present
   * with no steps" precedent rather than a new rule, and it announces the deploy and publishes like any other
   * green run.
   *
   * <p><b>The two kinds of {@code SKIPPED} stay apart by the output field</b>, which is the smallest
   * honest form: skipped-because-an-earlier-step-failed keeps its {@code null} output (the trailing
   * remainder loop below is untouched), and skipped-by-branch carries {@link #notBoundNote} — the
   * same bracketed convention {@link #annotate}/{@link #note} already use for every other "why this
   * row reads this way" sentence. No new status, no new column, no migration.
   */
  private void runSteps(
      CiRun run,
      CiPipeline pipeline,
      DaemonPin pin,
      Map<String, String> env,
      DeclaredRelease release) {
    List<CiPipeline.CiStepDecl> declared = pipeline.steps();
    int index = 0;
    boolean failed = false;

    try {
      while (index < declared.size() && !failed && !cancelled.contains(run.id)) {
        CiPipeline.CiStepDecl decl = declared.get(index);
        if (!decl.runsOnBranch(run.branch)) {
          // Recorded rather than passed over: the run shows that the pipeline considered this step
          // and why it did not run. No timestamps, because nothing started.
          insertStep(
              run.id,
              index,
              decl.image(),
              CiStepStatus.SKIPPED,
              null,
              notBoundNote(run.branch),
              null,
              null);
          index++;
          continue;
        }
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
      announceRun(run, finishedAt);
      announceRelease(run, finishedAt, release);
    }
  }

  /**
   * Announces a green run through the {@link RunAnnouncer} port — after the terminal row is
   * committed, so a consumer that reads the run back sees {@code SUCCESS}, and carrying the {@code
   * finishedAt} that was just written rather than a fresh {@code Instant.now()}: the two are minutes
   * apart in a slow transition and the event log wants the one on the row.
   *
   * <p><b>This is the only announcement a green run makes about itself</b>, and it used to be one of
   * two. The other was a direct POST to qits-platform-deployments' intake, sent from here on every
   * green run; the deployer now subscribes to {@code BuildSuccessful} on the bus durably and calls
   * its own announce path, so the deploy follows from this event instead of from a second call. The
   * intake is still there and is still the manual door a replay knocks on — what went is qits-ci
   * knocking on it.
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
   * Announces what a green <b>release pipeline</b> published — one announcement per declared
   * artifact, through {@link ReleaseAnnouncer}, after {@link #announceRun} because "this build
   * passed" is the more general statement of the two.
   *
   * <p><b>It is additional and never a replacement.</b> Every green run still announces itself
   * exactly as before; a declaration adds N events, it removes none. A run with no declaration —
   * every push and every ordinary event pipeline — reaches this method with a null and does nothing.
   *
   * <p><b>A declaration whose trigger carries no version publishes nothing, loudly.</b> The version
   * is not qits-ci's to invent: it belongs to the release the pipeline built, and the only place it
   * exists is the payload of the event that triggered the run. So a file declaring artifacts against
   * an event with no {@code version} field is a file written for a trigger that cannot feed it, and
   * the honest answer is a WARN naming the run and the event rather than an announcement with a
   * guessed or blank version — which downstream would install.
   */
  private void announceRelease(CiRun run, Instant finishedAt, DeclaredRelease release) {
    if (release == null) {
      return;
    }
    String version = versionOf(release.eventPayload());
    if (version == null) {
      LOG.warnf(
          "Run %s: %s declares %d artifact(s), but the %s event that triggered it carries no '%s' —"
              + " nothing published",
          run.id, run.configPath, release.artifacts().size(), run.triggerEventName, VERSION_FIELD);
      return;
    }
    for (CiArtifact artifact : release.artifacts()) {
      for (ReleaseAnnouncer announcer : releaseAnnouncers) {
        try {
          announcer.onArtifactPublished(
              run.id,
              run.repoId,
              version,
              artifact.type().declared(),
              artifact.name(),
              finishedAt,
              run.triggerEventId);
        } catch (RuntimeException e) {
          // Per artifact, so one failed announcement never costs the siblings theirs.
          LOG.warnf(e, "Announcing artifact %s of run %s failed", artifact.name(), run.id);
        }
      }
    }
  }

  /**
   * The triggering event's {@code version}, or null when there is none to read.
   *
   * <p>The payload is walked rather than bound, which is the trigger engine's rule and the same one
   * that keeps this path free of native-image reflection metadata. A non-string value is compared as
   * its JSON literal exactly as a selection would read it, so a version that arrives as a number is
   * announced as the digits it was written with rather than refused.
   */
  private static String versionOf(String payload) {
    JsonNode version =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(payload), VERSION_FIELD);
    if (version == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(version);
    return text.isBlank() ? null : text;
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

  /**
   * Records an accepted push as a {@code QUEUED} run: the constant config path, {@code
   * POST_RECEIVE}, and the id of the {@code SCMPublishCommit} that announced it. Called on the bus
   * adapter's thread, inside the claiming transaction of that event.
   *
   * <p><b>Two things collapse the moment {@code eventId} is non-null, and both are wanted.</b> The
   * run gains a causation parent for what it publishes; and it joins the unique constraint on
   * {@code (trigger_event_id, repo_id, config_path)}, which now means "one run per announced push"
   * as well as "one run per (event, trigger file)". A push's config path is the constant
   * {@code ci-post-receive.yml}, so the event id is the whole of what distinguishes one push row
   * from the next — which is exactly right, because two runs for one announcement are two builds of
   * one commit.
   *
   * <p>The durable claim already makes that near-unreachable: the funnel calls the listener at most
   * once per (consumer, event), so a redelivery does not arrive here at all. The constraint is the
   * backstop underneath, on ci's own datasource where the claim is not — and a duplicate is
   * <b>settled, not retried</b>: it comes back null, the caller enqueues nothing, and the event is
   * handled. Throwing would leave a push owed forever over a run that already exists.
   *
   * <p>A null {@code eventId} is the {@link #execute} test entry. Nothing is deduped for it, which
   * is what plain SQL {@code unique} gives for free — rows collide only when every column is
   * non-null and equal.
   *
   * @return the accepted run, or null when this push had already been recorded
   */
  private CiRun acceptPostReceive(String repoId, String branch, String sha, String eventId) {
    CiRun run = newRun(repoId, branch, sha);
    run.triggerType = CiTriggerType.POST_RECEIVE;
    run.configPath = CiConfigParser.CONFIG_PATH;
    run.triggerEventId = eventId;
    // No step rows: they are written one at a time, terminal, as each step ends.
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                runs.persist(run);
                runs.flush();
                for (CiRun older : runs.listQueuedPushes(repoId, branch, run.id)) {
                  older.status = CiRunStatus.FAILED;
                  older.finishedAt = run.createdAt;
                  older.cancellationReason = DEDUPED;
                  older.supersededByRunId = run.id;
                }
              });
    } catch (RuntimeException e) {
      if (eventId == null || !isUniqueViolation(e)) {
        throw e;
      }
      // The flush is what threw, so nothing was superseded either — which is right: a push that was
      // already recorded cannot cancel the queue a second time.
      LOG.infof(
          "Push %s already recorded a run for %s@%s — the first run stands", eventId, repoId, branch);
      return null;
    }
    return run;
  }

  /**
   * Claims a queued run for this worker: {@code QUEUED} becomes {@code RUNNING} and the row comes
   * back, or <b>null when the row is no longer queued</b> and there is nothing to run.
   *
   * <p>The null case is a cancellation that reached the row first, and reading the status inside the
   * claiming transaction is what makes "the worker must never pick up a run that was cancelled while
   * it waited" a property of the database rather than of the order two threads happened to run in.
   *
   * <p>Flipping here rather than at accept is what keeps {@code QUEUED} honest: the config read
   * below is an HTTP read against a host that can take seconds, and a run doing that has
   * started. It also fixes what a crash during it costs — a {@code RUNNING} row, swept to {@code
   * FAILED}, which is the truthful answer to "did this run begin".
   */
  private CiRun startQueued(String runId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CiRun run = runs.findById(runId);
              if (run == null || run.status != CiRunStatus.QUEUED) {
                return null;
              }
              run.status = CiRunStatus.RUNNING;
              return run;
            });
  }

  /** Writes the daemon build this run pinned, once, when the first container is about to launch. */
  private void pinDaemonVersion(String runId, String daemonVersion) {
    QuarkusTransaction.requiringNew()
        .run(() -> runs.findById(runId).daemonVersion = daemonVersion);
  }

  /**
   * Records an accepted event trigger as a {@code QUEUED} run, or <b>null when this (event,
   * repository, trigger file) already has one</b>.
   *
   * <p>Both halves of that are here on purpose. The {@link CiRunRepository#alreadyTriggered} query is
   * the cheap one and catches the ordinary case — a redelivery, which the bus is allowed to do and
   * which a future catch-up feature will do deliberately. The caught constraint violation is the one
   * that matters: it is the guarantee, it holds across a race and a restart in a way no read-then-write
   * can, and reaching it is not an error to report but the answer to a question. Anything that is
   * <em>not</em> a unique violation is rethrown, because a run that failed to insert for some other
   * reason is a defect and must not look like a duplicate.
   *
   * <p>Both run in <b>one</b> {@code requiringNew} bracket, which they have to for two reasons: the
   * caller is the trigger worker and a worker thread has no request context, so an unwrapped read
   * has no session at all; and a check in its own transaction would be answering about a moment that
   * has already passed by the time the insert happens.
   */
  /** The event id as a cause, or null for one that is not a UUID — never a reason to drop a run. */
  private static UUID parseCause(String eventId) {
    if (eventId == null) {
      return null;
    }
    try {
      return UUID.fromString(eventId);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private CiRun acceptEventRun(EventRun request) {
    String configPath = request.trigger().configPath();
    CiRun run = newRun(request.repoId(), request.branch(), request.sha());
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = configPath;
    run.triggerEventId = request.eventId();
    // The generic causation column gets the same value EXPLICITLY, because the ambient scope died
    // at the queue hop behind this call — CausationScope does not follow work, so the CausationStamp
    // listener would read null here and record a decision nobody made. An author-set value is what
    // the stamp yields to. Defensive parse: an id that is not a UUID costs the row its causation
    // edge and nothing else, the same trade CausingEvent.parentOf makes on the announce side.
    run.causationId = parseCause(request.eventId());
    run.triggerEventName = request.eventName();
    run.triggerEventOccurredAt = request.occurredAt();
    run.triggerEventPayload = request.payload();
    run.triggerConfig = request.triggerConfig();
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

  /**
   * A newly accepted run. Always {@code QUEUED}, never finished, and pinning no daemon: this class
   * writes exactly one kind of row now, and every state after it is a transition the worker makes.
   */
  private static CiRun newRun(String repoId, String branch, String sha) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.branch = branch;
    run.commitSha = sha;
    run.status = CiRunStatus.QUEUED;
    run.createdAt = Instant.now();
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
   * Stop a run that has not finished. A {@code RUNNING} one is flagged and its in-flight step's
   * container is asked to die; this returns as soon as both are done, which is well before the run
   * is actually finished — the caller answers 202.
   *
   * <p>A {@code QUEUED} one is <b>finished here</b>, {@code FAILED}, without the worker ever picking
   * it up. There is no container to ask and no step to stop, so the row is the whole of the
   * cancellation; the worker's own {@link #startQueued} then finds a row that is no longer queued
   * and drops it. That is the queue-visible form of what this class did before, when a cancellation
   * arriving before the first step tore the launch down instead.
   *
   * <p>The flag is raised in <b>both</b> cases and on purpose. Cancel runs on the request thread and
   * the claim runs on the worker; if the worker won the race and turned the row {@code RUNNING}
   * between the read and the write, the flag is what stops the run before its first container — so
   * neither thread has to win for the answer to be right.
   *
   * <p>Cancelling anything already terminal is a 409 rather than a quiet success: a finished run has
   * nothing to stop, and telling the caller it does would be a lie it cannot check.
   */
  public void cancel(String runId) {
    cancel(runId, null);
  }

  public void cancel(String runId, String requestedReason) {
    String reason = cancellationReason(requestedReason);
    CiRun run = requireRun(runId);
    if (run.status != CiRunStatus.RUNNING && run.status != CiRunStatus.QUEUED) {
      throw new ConflictException(
          "CI run " + runId + " is not running (" + run.status + ") — nothing to cancel");
    }
    cancelled.add(runId);
    boolean neverStarted =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  CiRun current = runs.findById(runId);
                  if (current == null || current.status != CiRunStatus.QUEUED) {
                    return false;
                  }
                  current.status = CiRunStatus.FAILED;
                  current.finishedAt = Instant.now();
                  current.cancellationReason = reason;
                  current.supersededByRunId = null;
                  return true;
                });
    if (neverStarted) {
      LOG.infof("CI run %s cancelled on request before it started (%s)", runId, reason);
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun current = runs.findById(runId);
              if (current != null) {
                current.cancellationReason = reason;
                current.supersededByRunId = null;
              }
            });
    runner.cancel(runId);
    LOG.infof("CI run %s cancelled on request (%s)", runId, reason);
  }

  private static String cancellationReason(String requestedReason) {
    if (requestedReason == null || requestedReason.isBlank()) {
      return USER_CANCELLED;
    }
    String reason = requestedReason.trim();
    if (reason.length() > MAX_CANCELLATION_REASON_LENGTH) {
      throw new eu.wohlben.qits.ci.error.BadRequestException(
          "Cancellation reason must be at most " + MAX_CANCELLATION_REASON_LENGTH + " characters");
    }
    return reason;
  }

  /**
   * Every accepted-but-unfinished run on this instance — {@code QUEUED} or {@code RUNNING} — newest
   * first, across all repositories. The read behind {@code GET /ci/api/runs/active}.
   *
   * <p>Unscoped, unlike everything else on this surface, because the question is "what is CI doing
   * right now" and that has no repository to scope to. It only became answerable when {@code QUEUED}
   * became a row: before, half of it lived in an executor's queue.
   */
  public List<CiRun> activeRuns() {
    return runs.listActiveNewestFirst();
  }

  /**
   * How many finished runs {@code GET /ci/api/runs/finished} answers with when the caller asks for no
   * particular number. Five, because the endpoint exists for a client that draws a short stack of
   * "what just happened" beside the runs in flight, and a default that has to be overridden to be
   * useful is not a default.
   */
  public static final int DEFAULT_FINISHED_LIMIT = 5;

  /**
   * The most finished runs one call will answer with, whatever it asks for.
   *
   * <p>This listing is the only one on the surface that is <b>both</b> unscoped by repository and
   * unbounded by anything else — the active list is bounded by what a single worker has accepted, and
   * a repository's own listing is bounded by that repository. Without a cap, {@code ?limit=} is an
   * unscoped listing of every run on the instance, which is precisely what {@code
   * CiRunController#listRuns} refuses to offer.
   *
   * <p>A larger ask is <b>clamped, not rejected</b>. The parameter has always been a bound rather
   * than a promise of n rows — {@code limit=50} over three runs answers with three and is not an
   * error — so answering an over-large ask with the most this endpoint will give is the same
   * contract, and a client that wants more history has a repository to scope to.
   */
  public static final int MAX_FINISHED_LIMIT = 100;

  /**
   * The newest finished runs across every repository, newest first — the read behind {@code GET
   * /ci/api/runs/finished}.
   *
   * @param limit how many to answer with, or null for {@link #DEFAULT_FINISHED_LIMIT}. Clamped to
   *     {@link #MAX_FINISHED_LIMIT}.
   * @throws BadRequestException if a limit is given and is not positive — the same rule {@link
   *     #runsFor(String, Integer)} applies, and for the same reason: zero rows is a question nobody
   *     asks and a negative bound is a caller bug rather than an empty answer
   */
  public List<CiRun> finishedRuns(Integer limit) {
    int asked = limit == null ? DEFAULT_FINISHED_LIMIT : limit;
    if (asked <= 0) {
      throw new BadRequestException("Invalid limit");
    }
    return runs.listFinishedNewestFirst(Math.min(asked, MAX_FINISHED_LIMIT));
  }

  /**
   * One row per repository this instance has recorded a run for, ascending by id: its newest run on
   * any branch, and its newest run on {@code main}.
   *
   * @param repositoryId the repository, exactly as {@link #repositoryIds} spells it
   * @param lastRun the newest run on any branch — never null, since a repository is only listed
   *     because it has one
   * @param lastMainRun the newest run on {@code main}, or null when every run it has is on another
   *     branch. It is frequently the same row as {@code lastRun}, and that is not a duplicate to
   *     collapse: a client asking "is main green" and a client asking "what happened last" are
   *     asking two questions that usually have one answer.
   */
  public record RepositorySummary(String repositoryId, CiRun lastRun, CiRun lastMainRun) {}

  /**
   * The summary behind {@code GET /ci/api/repositories/summary} — {@link #repositoryIds} with the
   * two runs a client would otherwise fetch a listing per repository to find.
   *
   * <p>Two queries per repository rather than one grouped query over everything. Both are index-hit
   * top-1 reads, the repository count is the number of repositories on the platform, and the
   * alternative — a window function or a fetch of every run — is either an ordering the entity
   * mapping would have to be taught or exactly the unbounded read this endpoint exists to spare the
   * client.
   */
  public List<RepositorySummary> repositorySummaries() {
    return repositoryIds().stream()
        .map(
            repoId ->
                new RepositorySummary(
                    repoId,
                    runs.newestFor(repoId).orElse(null),
                    runs.newestForBranch(repoId, MAIN_BRANCH).orElse(null)))
        // A repository is listed because it has runs, but the listing and these reads are separate
        // queries: a deletion in between must drop the entry rather than answer with a null lastRun.
        .filter(summary -> summary.lastRun() != null)
        .toList();
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
