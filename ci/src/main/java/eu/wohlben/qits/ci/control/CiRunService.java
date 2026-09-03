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
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
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
 * <h2>Patience: the READ surface holds through a short outage, and so do two request-path writes</h2>
 *
 * <p>The pool opens its connections through {@code PatientPgDriver}, so a request that has executed
 * nothing waits for postgres to come back rather than failing. What that cannot help is a connection
 * severed <b>mid-flight</b>, after the statements ran. The seven caller-facing reads at the bottom of
 * this class ({@link #activeRuns}, {@link #finishedRuns}, {@link #repositorySummaries}, {@link
 * #runsFor}, {@link #requireRun}, {@link #stepsFor}, {@link #repositoryIds}) are wrapped in {@link
 * DbRetry#call}, which sits <b>outside</b> {@code requiringNew}, never inside: inside one it would
 * re-run statements in a transaction the severed connection already doomed.
 *
 * <p><b>A write needs the other method, and it is not the same trade.</b> {@link DbRetry#inNewTx}
 * owns the transaction boundary, so it can retry the attempts that certainly did not commit — a
 * connection-class failure thrown out of the <em>body</em> — and rethrows everything the transaction
 * manager reports, because a lost commit acknowledgement is undecidable. Each wrapped body ends with
 * {@code runs.flush()} on purpose: an ORM flushes at commit by default, which would put the write on
 * the undecidable side of that line.
 *
 * <p><b>Two writes are wrapped, and they are the two on a request-shaped thread</b> — {@link
 * #acceptEventRun} and both transactions of {@link #cancel}. Their bodies are database-only: no
 * container is launched, no event published, no HTTP call made inside one, because the retry re-runs
 * the whole body. {@code acceptEventRun}'s check-then-insert-then-supersede is wrapped <b>whole</b>,
 * which is the only legal shape for it — splitting the bracket would cost the dedupe its race — and
 * that is sound because every statement in it is derived from the current database state and from the
 * arriving request, so a rolled-back attempt leaves the next one deciding the same way.
 *
 * <p><b>What is deliberately NOT wrapped.</b> {@link #acceptPostReceive} runs inside the durable
 * listener's claiming transaction, so a retry that outlives that claim's own connection would leave a
 * committed {@code QUEUED} row behind an event the funnel then re-offers — an orphan nothing
 * enqueues, where today the whole hop simply rolls back and the redelivery builds it cleanly. And no
 * worker-thread transition ({@link #startQueued}, {@link #finishRun}, {@code failIncompleteSteps},
 * {@link #discardRun}, the step inserts) is wrapped: those have their own recovery in {@code
 * sweepInterrupted} at boot, which is a stronger answer than a fifteen-second wait on the run worker.
 *
 * <p>The validation each listing does — a non-positive {@code limit} — happens <em>before</em> the
 * wrap, so a caller bug is still one immediate 400 rather than something retried for fifteen
 * seconds. {@link NotFoundException} is thrown inside the wrap and is not retried either, because
 * {@code DbRetry} waits on connection failures only; every other throwable propagates at once.
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
 *   <li>git host unreachable ⇒ the read is retried through {@link #UNREACHABLE_RETRY_DELAYS} first
 *       — long enough to sit out a git-host redeploy, which is when this legitimately happens —
 *       and only then <b>discarded</b>, warn-logged (a read failure must not invent a gate, and
 *       inventing one is exactly what leaving a red row behind would be);
 *   <li>the repository no longer holds the commit ⇒ <b>discarded</b>, including when the discovery
 *       happens later, in a step container's own checkout — the push it belonged to no longer
 *       exists, so a red run would blame a commit whose build was never broken;
 *   <li>config present but broken ⇒ {@link CiRunStatus#CONFIG_ERROR}, so the broken gate is
 *       visible;
 *   <li>config present with no steps ⇒ a trivially green run;
 *   <li>cancelled while still {@code QUEUED} ⇒ {@code CANCELLED}, with no steps — cancellation is a
 *       user decision rather than a failed pipeline verdict.
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

  /**
   * The gate a published artifact goes through — see {@link ReleaseJoin}. This class decides that a
   * release pipeline finished and what it published; that one decides whether the platform is
   * allowed to hear about it.
   */
  @Inject ReleaseJoin releaseJoin;

  /**
   * The field a release pipeline's version comes out of. It is the triggering event's payload, read
   * by name — {@code SCMRelease} carries it, and a trigger file declaring artifacts against an event
   * that does not, and that is not the tag event either, was written for something this cannot feed.
   */
  private static final String VERSION_FIELD = "version";

  /**
   * The git host's tag event, by the name it rides the bus under, and the payload field holding the
   * tag it announces. Together they are what makes {@link #supersedeByVersion} possible: an event
   * name this engine can recognise, and one field in its payload that orders.
   *
   * <p><b>Spelled as strings on purpose.</b> {@code qits-githost-events} is the {@code service}
   * module's dependency — the listener that binds {@code SCMPublishCommit} is what pays for it —
   * and this module deliberately has no compile-time knowledge of another context at all. What
   * keeps the strings honest is {@code bus/ScmPublishTagContractTest}, over in the module where the
   * record IS on the classpath: it resolves both against {@code SCMPublishTag} itself, so a rename
   * there is a red suite here rather than a supersede that quietly stops firing.
   */
  public static final String TAG_EVENT_NAME = "SCMPublishTag";

  public static final String TAG_NAME_FIELD = "tagName";

  /**
   * qits-projects' release-request event, by the name it rides the bus under, and the payload field
   * naming the request it is about.
   *
   * <p>A release request's backing branch is an octopus merge of N sources that qits-projects
   * rewrites on every change, and it publishes this event per successful re-fold. A repository's QA
   * pipeline selects it and checks out the fold — {@code checkout: { branch: backingBranch, sha:
   * mergedSha }} — and the verdict returns on the ordinary build events keyed on {@code (repoId,
   * commitSha)}. What that key cannot carry is <em>which request</em>, so the id is read here and
   * lands on {@link CiRun#releaseRequestId}: the sha is a fold nobody pushed and the next re-fold
   * replaces it, which makes the request id the only stable handle a cancellation or a retry can
   * address the work by.
   *
   * <p><b>Spelled as strings, exactly like {@link #TAG_EVENT_NAME}, and for a sharper version of the
   * same reason.</b> {@code ci} has no compile-time knowledge of another context — and here there is
   * no jar to have one of: <b>qits-projects publishes no vocabulary jar</b>, deliberately, the way
   * qits-workspaces does not (see {@code bus/ScmReleaseContractTest}). What keeps the two strings
   * honest is {@code bus/ReleaseRequestChangedContractTest}, which holds a transcription of the
   * published record and drives it through the real canonical serializer.
   *
   * <p><b>The engine gains no other knowledge of this event.</b> Matching, selection and checkout
   * are the generic grammar; these two constants buy exactly one provenance column.
   */
  public static final String RELEASE_REQUEST_EVENT_NAME = "ReleaseRequestChanged";

  public static final String RELEASE_REQUEST_ID_FIELD = "releaseRequestId";

  /** What {@code ci_run.release_request_id} can hold; a longer value is recorded as none. */
  static final int MAX_RELEASE_REQUEST_ID_LENGTH = 255;

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
   * How long a <b>read</b> holds while the datasource is gone — see "Patience" in this class's
   * javadoc. The shipped 15s covers a postgres cutover; the suite shortens it, because a test
   * proving the give-up must not pay for it.
   */
  @ConfigProperty(name = "qits.ci.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /**
   * qits-platform-artifacts' coordinates, for resolving a step image a recipe named without a
   * registry — see {@link CiStepImage} for the rule and for what it is worth. The same two keys the
   * publish scripts compose as {@code $QITS_REGISTRY} and {@code $QITS_IMAGE_REPOSITORY}, read here
   * so a recipe keeps naming no deployment fact of its own. RECEIVER-NAMED, like every other reader
   * of this pair.
   */
  @ConfigProperty(name = "qits.artifacts.registry-host")
  String artifactsRegistryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /**
   * The kill switch for that resolution, and it exists because of what it costs to be wrong.
   *
   * <p>Resolving points every step at the registry, so a registry that does not hold these images
   * yet breaks <b>every build in every repository at once</b> — and the pipeline that would publish
   * them runs on one of them, so there is no run left that could fix it. That is not a hypothetical:
   * on 2026-08-20 all five were absent from the registry <i>and</i> from the host, and an artifacts
   * store has been re-seeded without them before. Off, a deployment falls back to the local store
   * exactly as it always behaved, without rebuilding or redeploying this service.
   *
   * <p>Shipped ON, because the recoverable state is the one worth defaulting to and the failure is
   * loud, immediate and reversible by one variable.
   */
  @ConfigProperty(name = "qits.ci.resolve-platform-step-images", defaultValue = "true")
  boolean resolvePlatformStepImages;

  /**
   * Runs a user asked to stop. In memory and deliberately so: a cancellation is only meaningful
   * while the run it addresses is executing in <em>this</em> process, and a restart fails every
   * in-flight run anyway.
   */
  private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

  /**
   * Raised once this process is on its way out, and read by everything that would otherwise take
   * new work: {@link #enqueue} and the claim in {@link #startQueued}.
   *
   * <p><b>A dying process must claim nothing.</b> On 2026-08-23 a start-first successor booted, ran
   * its sweep, and only then did the predecessor claim a {@code QUEUED} row and die holding it
   * {@code RUNNING} — a row past every sweep, owned by no worker anywhere, which had to be flipped
   * by hand. Leaving the row {@code QUEUED} instead costs nothing: the successor's boot sweep
   * re-enqueues it.
   *
   * <p><b>The order is what makes the flag work.</b> {@link #onStop} observes {@code ShutdownEvent},
   * which Quarkus fires before it destroys beans — so the flag is up before {@link #shutdown}
   * stops the worker pool, and no worker can start a claim after it. A claim already past the read
   * commits and runs as normal in-flight work; that window is one transaction wide, and the run it
   * produces is an owned one.
   */
  private volatile boolean draining;

  /**
   * How long the worker keeps re-asking an unreachable git host for a pushed commit's config before
   * it decides. The schedule has to outlast a git-host <em>redeploy</em>, not a blip: the deploy
   * train bounces the git host and the next push's config read lands seconds later on a dead pooled
   * connection (2026-08-13, qits-containers — the discarded row cost the whole deploy, because the
   * announcing event was already consumed when the row was accepted). A full stop-first redeploy of
   * the git host has been observed to take over two minutes, so the schedule sums to almost five.
   */
  static final List<Duration> UNREACHABLE_RETRY_DELAYS =
      List.of(
          Duration.ofSeconds(2),
          Duration.ofSeconds(5),
          Duration.ofSeconds(10),
          Duration.ofSeconds(20),
          Duration.ofSeconds(30),
          Duration.ofSeconds(45),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60),
          Duration.ofSeconds(60));

  /** The schedule above, per instance so the suite can trade patience for speed. */
  private List<Duration> unreachableRetryDelays = UNREACHABLE_RETRY_DELAYS;

  /**
   * A method rather than a bare field, and that is load-bearing: this bean is normal-scoped, so
   * tests hold a client proxy — a field write would land on the proxy and change nothing.
   */
  void unreachableRetryDelays(List<Duration> delays) {
    unreachableRetryDelays = List.copyOf(delays);
  }

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

  /**
   * Stops this process taking new work, and runs <b>before</b> {@link #shutdown} — see {@link
   * #draining} for why the pair has to be in that order.
   */
  void onStop(@Observes ShutdownEvent event) {
    draining(true);
  }

  /**
   * A method rather than a bare field, for {@link #unreachableRetryDelays}'s reason: this bean is
   * normal-scoped, so a test holds a client proxy and a field write would land on the proxy. It is
   * also how a suite puts the flag back down — a real process never does.
   */
  void draining(boolean draining) {
    this.draining = draining;
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

  /** What one sweep found: work to restart, and the runs it failed — announced after the commit. */
  private record Sweep(List<String> requeue, List<FailedOrphan> failed, int restartedEvents) {}

  /** One orphaned run the sweep marked FAILED, with the instant the row was stamped with. */
  private record FailedOrphan(CiRun run, Instant finishedAt) {}

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
    if (!sweep.failed().isEmpty()) {
      LOG.infof(
          "Marked %d CI run(s) left RUNNING by a previous shutdown as FAILED",
          sweep.failed().size());
      // After the commit, like the re-enqueue below and for the same reason turned outward: a
      // consumer told the run failed must be able to read the terminal row back. An interrupted
      // run is a real failure of a real commit, so the per-commit ledger hears about it too.
      for (FailedOrphan orphan : sweep.failed()) {
        announceFailedRun(orphan.run(), orphan.finishedAt(), CiRunStatus.FAILED);
      }
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
    List<FailedOrphan> failed = new ArrayList<>();
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
        failed.add(new FailedOrphan(orphan, orphan.finishedAt));
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
      CiRepoRef repo, String branch, String oldSha, String newSha, String eventId) {
    // The name half is checked ONLY when it is there: an id-addressed push announces without it,
    // and refusing that would refuse every mirror sync and every pre-cutover platform.
    CiIdentifiers.requireRepo(repo);
    CiIdentifiers.requireBranch(branch);
    CiIdentifiers.requireSha(newSha);
    CiRun accepted = acceptPostReceive(repo, branch, newSha, eventId);
    if (accepted != null) {
      enqueue(accepted.id);
    }
  }

  /**
   * How a recorded run addresses its own repository: the storage id always, and the public
   * {@code (projectId, repoName)} pair when the announcing push carried one.
   *
   * <p>A method here rather than on the entity, because {@code ci/entity} is Panache rows and this
   * is the control layer's vocabulary — and because every reader of a run row wants exactly this
   * one question answered.
   */
  static CiRepoRef repoOf(CiRun run) {
    return new CiRepoRef(run.repoId, run.projectId, run.repoName);
  }

  /** What a run left behind for the successor is told, in one wording, from both places. */
  private static void logLeftQueuedWhileDraining(String runId) {
    LOG.infof(
        "CI run %s left QUEUED: this process is shutting down, the successor's boot sweep"
            + " re-enqueues it",
        runId);
  }

  /** Puts an already-accepted (QUEUED) run on the worker. */
  private void enqueue(String runId) {
    if (draining) {
      logLeftQueuedWhileDraining(runId);
      return;
    }
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
      CiRepoRef repo,
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
   * <p><b>There is a second collapse on this path, and it is not the same one.</b> The constraint
   * refuses a second run for one event; {@link #supersedeByVersion} refuses a second <em>queued</em>
   * run for one trigger file when both are tag events, keeping the newest tag of a multi-tag push
   * and no other. Distinct events, distinct rows, one build.
   *
   * @return the id of the run this recorded, or {@code null} when the dedupe dropped it. The manual
   *     trigger endpoint answers with these ids, which is what lets a 2xx there mean "these rows
   *     exist" rather than "something was handed to a queue".
   */
  public String onEventTrigger(EventRun request) {
    CiIdentifiers.requireRepo(request.repo());
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
        repoOf(run),
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
      CiRunStatus outcome =
          cancelled.contains(run.id) ? CiRunStatus.CANCELLED : CiRunStatus.FAILED;
      Instant finishedAt = finishRun(run.id, outcome);
      if (outcome == CiRunStatus.FAILED) {
        announceFailedRun(run, finishedAt, outcome);
      }
    } finally {
      cancelled.remove(run.id);
      runner.runClosed(run.id);
    }
  }

  /**
   * The config read, held through the window a git-host redeploy opens. {@code UNREACHABLE} is the
   * only answer retried: {@code ABSENT} and {@code GONE} are definite answers from a host that is
   * up, and the caller must decide on them at once. A cancellation ends the patience early — the
   * caller's switch then acts on whatever the last read said, exactly as if the schedule had run
   * out.
   */
  private ConfigLookup readConfigPatiently(CiRun run) {
    ConfigLookup lookup = configSource.read(repoOf(run), run.branch, run.commitSha);
    for (Duration delay : unreachableRetryDelays) {
      if (lookup.status() != ConfigLookup.Status.UNREACHABLE || cancelled.contains(run.id)) {
        return lookup;
      }
      LOG.warnf(
          "Could not read CI config for %s@%s — retrying in %ds",
          run.repoId, run.commitSha, delay.toSeconds());
      try {
        Thread.sleep(delay.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return lookup;
      }
      lookup = configSource.read(repoOf(run), run.branch, run.commitSha);
    }
    return lookup;
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
    execute(CiRepoRef.of(repoId), branch, sha);
  }

  /** The same, for a repository that carries its public coordinate. */
  void execute(CiRepoRef repo, String branch, String sha) {
    runQueued(acceptPostReceive(repo, branch, sha, null).id);
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
      // Somebody reached the row first — a cancellation of a still-queued run, which has already
      // written the terminal row, or this process draining, which left the row QUEUED for the
      // successor. Nothing to run and nothing to clean up but the flag a cancellation raced us with.
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

    ConfigLookup lookup = readConfigPatiently(run);
    if (cancelled.contains(run.id)) {
      finishRun(run.id, CiRunStatus.CANCELLED);
      return;
    }
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
        // the commit is very likely fine and this process simply could not ask. The read above has
        // already been patient through a whole git-host redeploy, so by here the host is properly
        // down, not bouncing.
        LOG.warnf(
            "Could not fetch %s from the git host after %d retries — no CI run recorded for %s",
            sha, unreachableRetryDelays.size(), repoId);
        discardRun(run.id);
        return;
      }
      case INVALID -> {
        LOG.infof("CI config unusable at %s@%s: %s", repoId, sha, lookup.message());
        announceFailedRun(run, finishRun(run.id, CiRunStatus.CONFIG_ERROR), CiRunStatus.CONFIG_ERROR);
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
      announceFailedRun(run, finishRun(run.id, CiRunStatus.CONFIG_ERROR), CiRunStatus.CONFIG_ERROR);
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
      CiRunStatus outcome =
          cancelled.contains(run.id) ? CiRunStatus.CANCELLED : CiRunStatus.FAILED;
      Instant finishedAt = finishRun(run.id, outcome);
      if (outcome == CiRunStatus.FAILED) {
        announceFailedRun(run, finishedAt, outcome);
      }
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
   * Where a declared step's image is really pulled from — see {@link CiStepImage}.
   *
   * <p><b>The resolved reference is what gets RECORDED as well as what gets started</b>, and the two
   * being one value is the point: a launch that fails on the image says which reference it could not
   * pull, rather than showing the recipe's shorthand and leaving the registry to be inferred.
   */
  private String stepImage(CiPipeline.CiStepDecl decl) {
    return resolvePlatformStepImages
        ? CiStepImage.resolve(decl.image(), artifactsRegistryHost, artifactsImageRepository)
        : decl.image();
  }

  /**
   * The sequential loop. Each iteration blocks on one container's whole lifetime and then writes
   * exactly one terminal row; whatever the loop did not reach is written {@code SKIPPED} at the end.
   *
   * <p><b>A step the run's branch does not bind is written {@code SKIPPED} before any container
   * exists, and nothing else moves</b>: {@code failed} is untouched, the loop continues, and later
   * steps run. It launches nothing and is a non-event to the run's verdict — precisely unlike a
   * {@code FAILED} or {@code TIMED_OUT} step, whose {@code failed = !ok} stops the loop and ends the
   * run. So a run whose every step is branch-skipped finishes green, which is the existing "config present
   * with no steps" precedent rather than a new rule, and it announces the deploy and publishes like any other
   * green run.
   *
   * <p><b>The two kinds of {@code SKIPPED} stay apart by the output field</b>, which is the smallest
   * honest form: skipped-because-an-earlier-step-failed keeps its {@code null} output (the trailing
   * remainder loop below is untouched), and skipped-by-branch carries {@link #notBoundNote} — the
   * same bracketed convention {@link #annotate}/{@link #note} already use for every other "why this
   * row reads this way" sentence. No new status, no new column, no migration.
   *
   * <h2>Which half the run died in decides what the verdict is worth</h2>
   *
   * <p>A step may declare {@code gating: false} ({@code CiPipeline.CiStepDecl}), and the run's
   * announced {@code gating} is <b>the file's flag ANDed with the failing step's</b>. That is the
   * whole mechanism behind putting a repository's gating build and its non-gating userflow publish
   * in ONE file: ordering does the rest, because the gating half runs first and has published
   * whatever it publishes before a non-gating step can fail. "A red verify must not cost the image"
   * therefore survives the merge of the two files it used to require.
   *
   * <p>Nothing else about failure moved. A non-gating step that fails <b>still stops the run</b> and
   * still leaves it {@code FAILED}, so a person sees the red exactly as before; what changes is only
   * what a release gate reads off the event.
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
    boolean timedOut = false;
    // Which half the run died in, and it is a Boolean because "no step failed" is a third answer:
    // a green run's verdict is worth what the FILE says and there is no step to ask.
    Boolean failedStepGating = null;

    try {
      while (index < declared.size() && !failed && !cancelled.contains(run.id)) {
        CiPipeline.CiStepDecl decl = declared.get(index);
        if (!decl.runsOnBranch(run.branch)) {
          // Recorded rather than passed over: the run shows that the pipeline considered this step
          // and why it did not run. No timestamps, because nothing started.
          insertStep(
              run.id,
              index,
              stepImage(decl),
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
                    repoOf(run),
                    run.branch,
                    run.commitSha,
                    stepImage(decl),
                    decl.script(),
                    pin.binaryUrl(),
                    decl.timeoutSeconds() == null ? stepTimeoutSeconds : decl.timeoutSeconds(),
                    decl.docker(),
                    decl.user(),
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
              configSource.read(repoOf(run), run.branch, run.commitSha).status()
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

        // A deadline is not a verdict, so it is recorded apart from FAILED — on the step and, below,
        // on the run. It still stops the loop exactly as a failure does: the remaining steps are
        // SKIPPED. Cancellation wins over it, because a cancelled step's deadline is beside the
        // point.
        boolean stepTimedOut = !wasCancelled && result.timedOut();
        boolean ok =
            !wasCancelled
                && !result.timedOut()
                && result.outcome() == StepOutcome.OK
                && result.exitCode() == 0;
        insertStep(
            run.id,
            index,
            stepImage(decl),
            ok
                ? CiStepStatus.SUCCESS
                : stepTimedOut ? CiStepStatus.TIMED_OUT : CiStepStatus.FAILED,
            result.exitCode(),
            annotate(result, wasCancelled),
            stamps.startedAt(),
            stamps.finishedAt());
        failed = !ok;
        if (failed) {
          failedStepGating = decl.gating();
        }
        timedOut = stepTimedOut;
        index++;
      }
    } catch (RuntimeException e) {
      // The step blew up instead of answering — an infrastructure error, not a pipeline verdict.
      // Record it against the step it happened on so no declared step vanishes from the run.
      LOG.errorf(e, "CI run %s: step %d failed unexpectedly", run.id, index);
      if (index < declared.size()) {
        // An infrastructure failure OF a non-gating step is still the non-gating half's: the run
        // died where the declaration says a death costs no gate.
        failedStepGating = declared.get(index).gating();
        Instant now = Instant.now();
        insertStep(
            run.id,
            index,
            stepImage(declared.get(index)),
            CiStepStatus.FAILED,
            null,
            "[the step could not be executed: " + e + "]",
            now,
            now);
        index++;
      }
      failed = true;
      timedOut = false;
    }

    for (int skipped = index; skipped < declared.size(); skipped++) {
      insertStep(
          run.id,
          skipped,
          stepImage(declared.get(skipped)),
          CiStepStatus.SKIPPED,
          null,
          null,
          null,
          null);
    }
    boolean wasCancelled = cancelled.contains(run.id);
    boolean red = failed || wasCancelled;
    CiRunStatus outcome =
        wasCancelled
            ? CiRunStatus.CANCELLED
            : timedOut
                ? CiRunStatus.TIMED_OUT
                : red ? CiRunStatus.FAILED : CiRunStatus.SUCCESS;
    // The verdict's own worth: the FILE's flag ANDed with the failing step's. A non-gating file
    // cannot be made gating by a step, and a gating file's non-gating half produces a red that no
    // release gate holds a commit for. It is written to the row as well as announced, so the two can
    // never disagree — and the detached copy is updated because the announcers read it off there.
    boolean verdictGating = run.gating && (failedStepGating == null || failedStepGating);
    Instant finishedAt = finishRun(run.id, outcome, verdictGating);
    run.gating = verdictGating;
    if (outcome == CiRunStatus.SUCCESS) {
      announceRun(run, finishedAt);
      announceRelease(run, finishedAt, release);
    } else if (outcome != CiRunStatus.CANCELLED) {
      announceFailedRun(run, finishedAt, outcome);
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
            run.id,
            run.repoId,
            run.projectId,
            run.repoName,
            run.branch,
            run.commitSha,
            run.gating,
            finishedAt,
            run.triggerEventId);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing run %s failed", run.id);
      }
    }
  }

  /**
   * {@link #announceRun}'s failure twin, called at every terminal write that says something true
   * about a commit: red and timed-out runs, config errors, a crash the worker caught, and the runs
   * a boot sweep found interrupted. What it is <b>not</b> called for is the contract's other half —
   * a cancelled run (a person withdrew the question) and a deduped-superseded row (bookkeeping
   * about the queue, not a fact about the commit) announce nothing, which is what lets a subscriber
   * keeping per-commit build status read every {@code BuildFailed} as a verdict.
   *
   * <p>Everything else — announce after the terminal row commits, the row's own {@code finishedAt},
   * the causation id off the row, failures being the port's and never the run's — is {@link
   * #announceRun}'s reasoning, unchanged.
   */
  private void announceFailedRun(CiRun run, Instant finishedAt, CiRunStatus outcome) {
    for (RunAnnouncer announcer : runAnnouncers) {
      try {
        announcer.onRunFailed(
            run.id,
            run.repoId,
            run.projectId,
            run.repoName,
            run.branch,
            run.commitSha,
            run.gating,
            outcome.name(),
            finishedAt,
            run.triggerEventId);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Announcing run %s failed", run.id);
      }
    }
  }

  /**
   * Hands what a green <b>release pipeline</b> published to {@link ReleaseJoin} — after {@link
   * #announceRun}, because "this build passed" is the more general statement of the two.
   *
   * <p><b>It is additional and never a replacement.</b> Every green run still announces itself
   * exactly as before; a declaration adds N events, it removes none. A run with no declaration —
   * every push and every ordinary event pipeline — reaches this method with a null and does nothing.
   *
   * <p><b>Whether the platform hears about it is not decided here.</b> A green run says what it
   * published; {@link ReleaseJoin} says whether that was a release or a bootstrap replay restoring a
   * tag. This method's job ends at the key, and the key is where the two halves have to agree — see
   * {@link #releaseVersionOf}.
   *
   * <p><b>A declaration whose trigger carries no version publishes nothing, loudly.</b> The version
   * is not qits-ci's to invent: it belongs to the release the pipeline built, and the only place it
   * exists is the payload of the event that triggered the run. So a file declaring artifacts against
   * an event that carries neither a {@code version} nor a tag name is a file written for a trigger
   * that cannot feed it, and the honest answer is a WARN naming the run and the event rather than an
   * announcement with a guessed or blank version — which downstream would install.
   */
  private void announceRelease(CiRun run, Instant finishedAt, DeclaredRelease release) {
    if (release == null) {
      return;
    }
    String version = releaseVersionOf(run.triggerEventName, release.eventPayload());
    if (version == null) {
      LOG.warnf(
          "Run %s: %s declares %d artifact(s), but the %s event that triggered it carries neither"
              + " '%s' nor '%s' — nothing published",
          run.id,
          run.configPath,
          release.artifacts().size(),
          run.triggerEventName,
          VERSION_FIELD,
          TAG_NAME_FIELD);
      return;
    }
    releaseJoin.onGreenReleaseRun(
        new ReleaseJoin.Published(
            run.id,
            run.repoId,
            run.repoName,
            version,
            run.triggerEventName,
            run.triggerEventId,
            finishedAt,
            release.artifacts()));
  }

  /**
   * The version a green release pipeline published under, taken from the event that triggered it, or
   * null when there is none to read.
   *
   * <p><b>Two events feed it and they spell it differently, which is why this is one method rather
   * than one field name.</b> An {@code SCMRelease} carries {@code version}. An {@code
   * SCMPublishTag} carries {@code tagName}, and that IS the version string: a release stamp is the
   * name of the tag the release push created, so a tag-triggered release pipeline and the {@code
   * SCMRelease} for the same release land on the same key. Both spellings had to be readable here
   * for {@link ReleaseJoin} to have anything to join — bootstrap-replay-plan.md's WP1 is what moves
   * the release recipes onto the tag event.
   *
   * <p>The payload is walked rather than bound, which is the trigger engine's rule and the same one
   * that keeps this path free of native-image reflection metadata. A non-string value is read as its
   * JSON literal exactly as a selection would read it, so a version that arrives as a number is
   * announced as the digits it was written with rather than refused.
   */
  private static String releaseVersionOf(String eventName, String payload) {
    return TAG_EVENT_NAME.equals(eventName) ? tagOf(payload) : versionOf(payload);
  }

  /** The triggering event's {@code version}, or null when there is none to read. */
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
  private CiRun acceptPostReceive(CiRepoRef repo, String branch, String sha, String eventId) {
    String repoId = repo.repoId();
    CiRun run = newRun(repo, branch, sha);
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
   * <p>It is also <b>null while this process is {@link #draining}</b>, and then the row is left
   * {@code QUEUED} rather than finished: a shutting-down process must hand its backlog on, not claim
   * it.
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
              // Read as late as it can be — after the row, immediately before the flip, inside the
              // claiming transaction. A dying process leaves the row QUEUED for the successor's
              // boot sweep rather than claiming it and dying holding it RUNNING.
              if (draining) {
                logLeftQueuedWhileDraining(runId);
                return null;
              }
              run.status = CiRunStatus.RUNNING;
              run.startedAt = Instant.now();
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
    CiRun run;
    try {
      run =
          DbRetry.inNewTx(
              "event run accept", () -> insertEventRun(request, configPath), retryDeadline());
    } catch (RuntimeException e) {
      if (!isUniqueViolation(e)) {
        throw e;
      }
      LOG.infof(
          "Event %s reached %s in %s twice — the first run stands",
          request.eventId(), configPath, request.repo().display());
      return null;
    }
    if (run == null) {
      LOG.debugf(
          "Event %s already triggered %s in %s — no second run",
          request.eventId(), configPath, request.repo().display());
      return null;
    }
    return run;
  }

  /**
   * The whole bracket as one database-only unit: the check, the insert and the supersede, returning
   * the accepted run or null for one this event already triggered.
   *
   * <p><b>The row is built HERE rather than by the caller</b>, and that is what makes a second
   * attempt correct. A rolled-back attempt leaves its entity carrying whatever {@link
   * #supersedeByVersion} wrote on it — a run that lost to a queued tag is marked {@code FAILED} in
   * memory — so re-persisting that same instance against a database that has since changed would
   * commit a decision the second attempt never made. A fresh instance per attempt has no memory of
   * one.
   *
   * <p>Nothing in here is anything but a database statement, because {@link DbRetry#inNewTx} re-runs
   * the whole body. The trailing {@code flush} is the second half of that contract: it moves the
   * supersede's updates into the statement phase, where a lost connection is a certain no-commit.
   */
  private CiRun insertEventRun(EventRun request, String configPath) {
    if (runs.alreadyTriggered(request.eventId(), request.repo().repoId(), configPath)) {
      return null;
    }
    CiRun run = newRun(request.repo(), request.branch(), request.sha());
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
    run.gating = request.trigger().gating();
    run.releaseRequestId = releaseRequestOf(request);
    runs.persist(run);
    runs.flush();
    supersedeByVersion(run, request);
    supersedeByCheckoutBranch(run, request);
    runs.flush();
    return run;
  }

  /**
   * <b>A burst of pushes to one branch is one build, of the newest tip.</b> The event path's
   * <em>third</em> collapse, and the checkout feature's twin of the per-branch push supersede in
   * {@link #acceptPostReceive}: a trigger following the event's own branch gets one run per push,
   * so N pushes in a burst would queue N builds behind the single run worker where only the newest
   * matters.
   *
   * <p><b>The {@code checkout != null} gate is correctness, not tidiness.</b> Without {@code
   * checkout:} every event run's branch is {@code main} by convention, so a branch-keyed collapse
   * would dedupe runs of <em>distinct events</em> that merely share the convention — which the
   * {@code (trigger_event_id, …)} contract forbids.
   *
   * <p>Accepted always wins, {@code QUEUED} rows only, convergence not minimality — {@link
   * #acceptPostReceive}'s exact rules, including the out-of-order-redelivery exposure the push path
   * already accepts. Called inside {@link #acceptEventRun}'s transaction, after the flush, beside
   * {@link #supersedeByVersion} (the two cannot both fire: one is gated on {@code SCMPublishTag},
   * whose payload names no branch and therefore parses into no checkout).
   */
  private void supersedeByCheckoutBranch(CiRun accepted, EventRun request) {
    if (request.trigger().checkout() == null) {
      return;
    }
    for (CiRun queued :
        runs.listQueuedEventRunsOnBranch(
            accepted.repoId,
            accepted.configPath,
            request.eventName(),
            accepted.branch,
            accepted.id)) {
      LOG.infof(
          "Push run %s at %s@%s supersedes queued %s — a burst builds the newest tip only",
          accepted.id, accepted.repoId, accepted.branch, queued.id);
      dedupe(queued, accepted);
    }
  }

  /**
   * <b>One push carrying many tags is one build, of the newest tag.</b> Every accepted tag-triggered
   * run collapses against the other queued runs of the same trigger file: the lower tag by {@link
   * VersionSort} is marked {@code DEDUPED}, which may be the run just accepted.
   *
   * <p>It is the event path's twin of the branch supersede in {@link #acceptPostReceive}, down to
   * the columns it writes, and it exists for the same reason. A release push writes N tag refs and
   * the git host announces <b>every one of them</b> — deliberately, because {@code SCMPublishTag} is
   * a fact about the repository and qits-projects' backup consumer needs them all. So the collapse
   * cannot live in the publisher, and it lives here, in the consumer that turns a fact into work.
   *
   * <p><b>Best-effort by design, exactly like the branch supersede.</b> Only {@code QUEUED} rows are
   * touched: a lower tag whose run has already started keeps running, because cancelling a running
   * build to save the time it has already spent is a worse trade than letting it finish. What is
   * guaranteed is convergence, not minimality — N tags in one push leave one run to do.
   *
   * <p>Three things are deliberately left alone. A payload neither side can be read a tag out of
   * supersedes nothing, since "no tag" is not a lower version — it is a failure to compare, and a
   * failure to compare must not cancel a build. A tag equal to a queued one <em>does</em> supersede
   * it, because that is a tag that moved and the newer announcement is the current one, which is the
   * same rule the branch supersede applies to a second push. And a non-tag event never enters here
   * at all: no other event on this bus carries a field this can order by.
   *
   * <p><b>A row names what beat it at the time, so out-of-order tags leave a chain rather than a
   * star.</b> Only queued rows are candidates, so a run superseded three tags ago is no longer one —
   * its {@code supersededByRunId} keeps pointing at the run that beat it then, which is a true
   * statement about that moment and reaches the surviving run by following it. Rewriting the older
   * rows to name the latest winner would be tidier and would say something no thread ever decided.
   *
   * <p>Called inside {@link #acceptEventRun}'s transaction, after the flush, so a row it supersedes
   * and the row that superseded it commit together or not at all.
   */
  private void supersedeByVersion(CiRun accepted, EventRun request) {
    if (!TAG_EVENT_NAME.equals(request.eventName())) {
      return;
    }
    String tag = tagOf(request.payload());
    if (tag == null) {
      return;
    }
    CiRun newer = null;
    String newerTag = null;
    for (CiRun queued :
        runs.listQueuedEventRuns(
            accepted.repoId, accepted.configPath, TAG_EVENT_NAME, accepted.id)) {
      String queuedTag = tagOf(queued.triggerEventPayload);
      if (queuedTag == null) {
        continue;
      }
      if (VersionSort.compare(tag, queuedTag) >= 0) {
        dedupe(queued, accepted);
      } else if (newerTag == null || VersionSort.compare(queuedTag, newerTag) > 0) {
        newer = queued;
        newerTag = queuedTag;
      }
    }
    if (newer != null) {
      // The push's tags arrived out of order and this one is not the newest. Its row stays as the
      // record that the tag was announced; the worker's claim reads the status back and drops it,
      // which is the same path a cancellation of a queued run takes.
      LOG.infof(
          "Tag %s in %s is older than queued %s — run %s stands for %s",
          tag, accepted.repoId, newerTag, newer.id, accepted.configPath);
      dedupe(accepted, newer);
    }
  }

  /** Marks one queued run superseded by another. The idiom both supersedes write, spelled once. */
  private static void dedupe(CiRun loser, CiRun winner) {
    loser.status = CiRunStatus.FAILED;
    loser.finishedAt = winner.createdAt;
    loser.cancellationReason = DEDUPED;
    loser.supersededByRunId = winner.id;
  }

  /**
   * The release request this event run serves, or null when it serves none.
   *
   * <p><b>Gated on the event NAME</b>, the way {@link #supersedeByVersion} is gated on the tag
   * event's. A {@code releaseRequestId} elsewhere on the bus would be some other context's word,
   * and a provenance column that reads any field of any payload is one that eventually records
   * something nobody meant.
   *
   * <p>Walked rather than bound, the trigger engine's rule and the one that keeps this path free of
   * native-image reflection metadata. A value too long for the column is recorded as <b>none</b>
   * rather than truncated or thrown: the run is the point and a payload that cannot name a request
   * within 255 characters is not naming one this platform issued.
   */
  private static String releaseRequestOf(EventRun request) {
    if (!RELEASE_REQUEST_EVENT_NAME.equals(request.eventName())) {
      return null;
    }
    JsonNode id =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(request.payload()), RELEASE_REQUEST_ID_FIELD);
    if (id == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(id);
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > MAX_RELEASE_REQUEST_ID_LENGTH) {
      LOG.warnf(
          "Event %s (%s) names a '%s' of %d characters — too long to record, the run keeps none",
          request.eventId(), request.eventName(), RELEASE_REQUEST_ID_FIELD, text.length());
      return null;
    }
    return text;
  }

  /**
   * The tag an {@code SCMPublishTag} payload announces, or null when there is none to read — an
   * unparseable payload, a missing field, or a blank one. Walked rather than bound, the same rule
   * {@link #versionOf} follows and for the same native-image reason.
   */
  private static String tagOf(String payload) {
    JsonNode tag =
        CiEventSelectionEvaluator.resolve(
            CiEventSelectionEvaluator.parsePayload(payload), TAG_NAME_FIELD);
    if (tag == null) {
      return null;
    }
    String text = CiEventSelectionEvaluator.asString(tag);
    return text.isBlank() ? null : text;
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
  private static CiRun newRun(CiRepoRef repo, String branch, String sha) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repo.repoId();
    // Null when the announcing push was id-addressed — the row then reads exactly as every row
    // recorded before this campaign, and every URL built from it is the id-addressed one.
    run.projectId = repo.projectId();
    run.repoName = repo.name();
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
    return finishRun(runId, status, null);
  }

  /**
   * The same, also writing what the <b>verdict</b> is worth to a release gate. Null leaves the
   * column alone, which is every terminal transition that is not the step loop's own: a config
   * error, a cancellation and a swept orphan have no failing step to classify, so the file's own
   * flag stands.
   */
  private Instant finishRun(String runId, CiRunStatus status, Boolean gating) {
    Instant finishedAt = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(runId);
              run.status = status;
              run.finishedAt = finishedAt;
              if (gating != null) {
                run.gating = gating;
              }
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
   * <p>A {@code QUEUED} one is <b>finished here</b>, {@code CANCELLED}, without the worker ever picking
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
   * <p><b>A {@code RUNNING} row no worker of this process owns is settled here, in one write.</b>
   * That row is what a dead predecessor leaves: its worker went with its container, so
   * {@link CiStepRunner#cancel} would ask nothing of nobody and nothing would ever write the
   * terminal row. So the incomplete steps are failed and the run is finished {@code CANCELLED} with
   * the caller's reason, and the runner is not called at all. Measured 2026-08-23, when a
   * start-first successor swept and the dying predecessor then claimed a queued row: the survivor
   * had to be flipped by hand in SQL, because cancel only ever recorded a reason on it.
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
    // Both writes are held through a short outage: this runs on the request thread, outside any
    // transaction of its own, and each body is nothing but statements — the flag above and the
    // runner below are outside the retry precisely because they are not.
    boolean neverStarted =
        DbRetry.inNewTx(
            "cancel a queued run",
            () -> {
              CiRun current = runs.findById(runId);
              if (current == null || current.status != CiRunStatus.QUEUED) {
                return false;
              }
              current.status = CiRunStatus.CANCELLED;
              current.finishedAt = Instant.now();
              current.cancellationReason = reason;
              current.supersededByRunId = null;
              runs.flush();
              return true;
            },
            retryDeadline());
    if (neverStarted) {
      LOG.infof("CI run %s cancelled on request before it started (%s)", runId, reason);
      return;
    }
    if (!runner.owns(runId)) {
      // Nobody here is running it, so there is nothing to ask to stop and nothing that will ever
      // write the terminal row. Settle it in one write instead of recording a reason on a row that
      // would stay RUNNING forever.
      DbRetry.runInNewTx(
          "settle a running run no worker owns",
          () -> {
            CiRun current = runs.findById(runId);
            if (current == null) {
              return;
            }
            failIncompleteSteps(runId);
            current.status = CiRunStatus.CANCELLED;
            current.finishedAt = Instant.now();
            current.cancellationReason = reason;
            current.supersededByRunId = null;
            runs.flush();
          },
          retryDeadline());
      LOG.infof(
          "CI run %s was RUNNING with no worker in this process — settled as CANCELLED on request",
          runId);
      return;
    }
    DbRetry.runInNewTx(
        "record a running run's cancellation reason",
        () -> {
          CiRun current = runs.findById(runId);
          if (current != null) {
            current.cancellationReason = reason;
            current.supersededByRunId = null;
            runs.flush();
          }
        },
        retryDeadline());
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
    return DbRetry.call(
        "active run listing",
        () -> QuarkusTransaction.requiringNew().call(runs::listActiveNewestFirst),
        retryDeadline());
  }

  /**
   * The configured deadline, or the library's default for an instance nobody injected — which is how
   * a hand-built test subclass arrives, constructed with {@code new} and calling {@code super} for
   * the part it does not fake.
   */
  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
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
    int bounded = Math.min(asked, MAX_FINISHED_LIMIT);
    return DbRetry.call(
        "finished run listing",
        () -> QuarkusTransaction.requiringNew().call(() -> runs.listFinishedNewestFirst(bounded)),
        retryDeadline());
  }

  /**
   * One row per repository this instance has recorded a run for, ascending by id: its newest run on
   * any branch, and its newest run on {@code main}.
   *
   * @param repositoryId the repository, exactly as {@link #repositoryIds} spells it — the storage
   *     id, which is what the runs are grouped by and what stays stable across a rename
   * @param projectId the owning project, or null when no run of this repository carries the public
   *     coordinate. Read off the newest run rather than stored anywhere: qits-ci owns no repository
   *     row, so what it knows about a name is whatever the last push told it.
   * @param repoName the repository's public name under the same rule, or null
   * @param lastRun the newest run on any branch — never null, since a repository is only listed
   *     because it has one
   * @param lastMainRun the newest run on {@code main}, or null when every run it has is on another
   *     branch. It is frequently the same row as {@code lastRun}, and that is not a duplicate to
   *     collapse: a client asking "is main green" and a client asking "what happened last" are
   *     asking two questions that usually have one answer.
   */
  public record RepositorySummary(
      String repositoryId, String projectId, String repoName, CiRun lastRun, CiRun lastMainRun) {}

  /**
   * The summary behind {@code GET /ci/api/repositories/summary} — {@link #repositoryIds} with the
   * two runs a client would otherwise fetch a listing per repository to find.
   *
   * <p>Two queries per repository rather than one grouped query over everything. Both are index-hit
   * top-1 reads, the repository count is the number of repositories on the platform, and the
   * alternative — a window function or a fetch of every run — is either an ordering the entity
   * mapping would have to be taught or exactly the unbounded read this endpoint exists to spare the
   * client.
   *
   * <p><b>The whole fan-out is one transaction and one retry.</b> The listing and the 2N reads used
   * to be 2N+1 unbracketed queries; holding them together is what lets a connection lost halfway
   * through be answered by re-reading the lot rather than by half a summary, and it costs nothing —
   * every one of them is an index-hit read.
   */
  public List<RepositorySummary> repositorySummaries() {
    return DbRetry.call(
        "repository summary listing",
        () -> QuarkusTransaction.requiringNew().call(this::readSummaries),
        retryDeadline());
  }

  private List<RepositorySummary> readSummaries() {
    return sortedRepoIds().stream()
        .map(
            repoId -> {
              CiRun last = runs.newestFor(repoId).orElse(null);
              return new RepositorySummary(
                  repoId,
                  last == null ? null : last.projectId,
                  last == null ? null : last.repoName,
                  last,
                  runs.newestForBranch(repoId, MAIN_BRANCH).orElse(null));
            })
        // A repository is listed because it has runs, but the listing and these reads are separate
        // queries: a deletion in between must drop the entry rather than answer with a null lastRun.
        .filter(summary -> summary.lastRun() != null)
        .toList();
  }

  /** All runs recorded for a repository, newest-first. */
  public List<CiRun> runsFor(String repoId) {
    return DbRetry.call(
        "run listing for repository " + repoId,
        () -> QuarkusTransaction.requiringNew().call(() -> runs.listByRepoIdNewestFirst(repoId)),
        retryDeadline());
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
    return DbRetry.call(
        "run listing for repository " + repoId,
        () ->
            QuarkusTransaction.requiringNew()
                .call(() -> runs.listByRepoIdNewestFirst(repoId, limit)),
        retryDeadline());
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
    return DbRetry.call(
        "repository id listing",
        () -> QuarkusTransaction.requiringNew().call(this::sortedRepoIds),
        retryDeadline());
  }

  /**
   * {@link #repositoryIds} without the patience, so {@link #repositorySummaries} can read the ids
   * inside its own transaction and its own retry rather than nesting one retry in another.
   */
  private List<String> sortedRepoIds() {
    return runs.distinctRepoIds().stream().sorted().toList();
  }

  /**
   * The run, or 404.
   *
   * <p>The {@link NotFoundException} is raised inside the retry and is not retried: {@code DbRetry}
   * waits on connection failures only, so an absent run is still one immediate 404.
   */
  public CiRun requireRun(String runId) {
    return DbRetry.call(
        "run lookup " + runId,
        () ->
            QuarkusTransaction.requiringNew()
                .call(
                    () ->
                        runs.findByIdOptional(runId)
                            .orElseThrow(() -> new NotFoundException("No such CI run: " + runId))),
        retryDeadline());
  }

  /** A run's steps in declaration order. */
  public List<CiStep> stepsFor(String runId) {
    return DbRetry.call(
        "step listing for run " + runId,
        () -> QuarkusTransaction.requiringNew().call(() -> steps.listByRunIdOrdered(runId)),
        retryDeadline());
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
