package eu.wohlben.qits.ci.control;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import eu.wohlben.qits.ci.entity.CiOwedEvent;
import eu.wohlben.qits.ci.persistence.CiOwedEventRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The trigger engine: one arriving domain event → which repositories declare a pipeline for it →
 * enqueue the runs.
 *
 * <p>It sits behind the bus rather than in it. The raw-listener bean that receives frames is {@code
 * service/…/bus/CiEventTriggerListener}, and it hands one over as an {@link Arrival} — a record of
 * four strings — so this module keeps knowing nothing about {@code eu.wohlben.qits.eventstream},
 * exactly as {@link RunAnnouncer} keeps it from knowing about the publishing half.
 *
 * <h2>The executor, and why it is not the dispatch thread and not the run worker</h2>
 *
 * <p><b>Not the dispatch thread.</b> {@code onFrame} runs on the bus's websocket worker, one frame at
 * a time for the whole subscription. Evaluation reads the git host <em>per candidate repository</em>,
 * so doing it inline would hold up every other consumer's frames for however long a git host takes —
 * and the typed {@code BuildSuccessfulListener} is on that same thread.
 *
 * <p><b>Not {@code ci-run-worker} either</b>, though it is the obvious reuse. That thread is occupied
 * by a running pipeline for minutes at a time; queueing evaluation behind it would mean an event that
 * arrived during a long build gets evaluated when the build ends, against a {@code main} that has
 * moved since. Evaluation-before-enqueue is a different latency class from run execution and gets its
 * own thread.
 *
 * <p><b>Single-threaded, though.</b> It was once a correctness rule — two evaluations of one
 * repository raced for the same bare cache on disk — and the caches are gone, so what is left is a
 * budget: one evaluation reads the git host once per candidate repository, and a thread per arriving
 * frame would point that fan-out at the host all at once. It matches the run worker's own shape.
 *
 * <p>The queue is <b>bounded</b>. An unbounded one turns a burst on the bus into heap; a bounded one
 * turns it into a WARN naming the event that was dropped, which is a thing a person can act on. At
 * {@link #QUEUE_CAPACITY} deep that is a backlog no healthy platform reaches.
 *
 * <h2>The manual trigger does not use that worker at all, and the reason was measured</h2>
 *
 * <p>{@link #evaluateNow} evaluates on the <b>caller's own thread</b> and answers what it did. Every
 * other way in is redeliverable — a bus frame that is not evaluated stays owed and the next catch-up
 * sweep offers it again — and a caller-supplied event is not: it is on no log, has no claim row and
 * nothing anywhere will ever offer it a second time. So for that one caller, "handed to a queue" and
 * "lost" are the same outcome, and the 2026-08-10 bootstrap measured it: an accepted trigger whose
 * evaluation never happened, answered 2xx, never run, with no line at any level to say so.
 *
 * <p>Two things could produce that and this closes both. The queue can be full, which {@link
 * #onEvent} reports as {@code false} — a signal the endpoint discarded. And the single worker can be
 * slow or stuck inside a git-host read, which nothing reports at all: the task simply waits in the
 * queue. Neither can reach a caller that runs the evaluation itself.
 *
 * <p><b>The cost is stated rather than hidden.</b> "One git-host fan-out at a time" is now a
 * statement about bus traffic only; a manual call fans out beside the worker. That is the right way
 * round — the budget exists to keep a burst of machine events from storming the git host, and a
 * manual trigger is one request from one person, already bounded by the HTTP worker pool.
 *
 * <h2>An accepted event is DURABLE, and that is what the enqueue used to cost</h2>
 *
 * <p>The residual window this class used to state plainly — "the claim commits when the event is
 * ACCEPTED, not when the run row exists; a crash in the gap loses that event" — is <b>closed</b>.
 * It was measured on 2026-09-04: three release requests created within a second of a qits-ci
 * redeploy were consumed without effect, their {@code ReleaseRequestChanged} claimed by the dying
 * instance and their QA runs never recorded, and with the release gate strictly requiring verdicts
 * they hung PENDING until they were withdrawn and recreated. Milliseconds are exactly as wide as a
 * cutover chooses to make them.
 *
 * <p>{@link #onEvent} now writes a {@link CiOwedEvent} row on ci's own datasource, in its own
 * transaction, <b>before</b> it reports the acceptance — and {@link #evaluateQuietly} deletes it the
 * moment the evaluation returns. The claim cannot join that transaction (it lives on the eventstream
 * datasource, and one JTA transaction does not take both), so what is made atomic is the pair that
 * can be: the acceptance is durable before it is claimable.
 *
 * <ul>
 *   <li>dying before the row commits: the accept answers {@code false}, the listener throws, the
 *       claim rolls back and the bus offers the event again — the existing retryable path.
 *   <li>dying after it and before the run row: the claim stands, and the row is the record that the
 *       event was never evaluated. {@link #sweepOwed} re-evaluates it at boot and on a schedule.
 * </ul>
 *
 * <p><b>Re-evaluating is safe by construction rather than by care</b>: {@code unique
 * (trigger_event_id, repo_id, config_path)} makes a second evaluation of an event that already
 * recorded its runs a no-op. That constraint is what lets this ledger be at-least-once.
 *
 * <p><b>What the ledger does NOT do is retry an evaluation that happened.</b> A sweep settles a row
 * whenever the evaluation returns, including one that reached no readable repository — that case is
 * the git host's, it behaves exactly as a live frame's evaluation does, and making it retryable here
 * would keep rows for a platform that simply has no candidates yet. Only a <em>throw</em> leaves the
 * row owed.
 *
 * <h2>Platform pipelines</h2>
 *
 * <p>There is a <b>second source of trigger files</b>: {@code .config/qits/ci-platform-event-*.yml}
 * in the one repository {@code qits.ci.platform-pipelines-repository} names, at its {@code main}
 * head. Such a file is parsed and selected exactly like a repository's own — same grammar, same
 * schema, same per-file containment — but the run it records is about the repository the
 * <b>payload</b> names, so one file serves the whole catalogue. See {@link #evaluatePlatform}.
 *
 * <p>It costs <b>one</b> extra listing per arriving event and no extra read per candidate: the head
 * a platform run is recorded at is the one the candidate loop already resolved for that repository.
 * A blank config key means the feature is off and nothing is read at all.
 */
@ApplicationScoped
public class CiEventTriggerService {

  private static final Logger LOG = Logger.getLogger(CiEventTriggerService.class);

  /**
   * The branch an event trigger reads, and — unless the file declares {@code checkout:} — the one
   * its run builds. The platform's one tracked branch, supplied by convention because most events
   * name no ref. A trigger with {@code checkout:} still <b>decides</b> here ("decide at main, build
   * at the event's commit"): discovery, parsing and selection read this branch's head, so a pushed
   * branch cannot alter the CI that gates it; only the recorded run's branch/sha come from the
   * payload.
   */
  public static final String TRIGGER_BRANCH = "main";

  /** Deep enough that reaching it means something is wrong rather than something is busy. */
  static final int QUEUE_CAPACITY = 256;

  /**
   * The payload field a platform pipeline's run is about. A platform trigger file names no
   * repository — it is one file for the catalogue — so the event has to, and this is the one word
   * that contract is spelled in.
   */
  static final String PAYLOAD_REPOSITORY_FIELD = "repository";

  @Inject CiConfigSource configSource;
  @Inject CiEventTriggerParser triggerParser;
  @Inject CiCandidateRepos candidateRepos;
  @Inject CiRunService runService;
  @Inject CiOwedEventRepository owed;

  /**
   * How long {@link #evaluateNow} keeps asking repositories. It runs on a request thread and reads
   * the git host once per candidate, so an unanswering host would otherwise hold the caller for
   * candidates times the read timeout. Past the deadline the repositories not yet asked are reported
   * as skipped, which is the truth about them.
   */
  @ConfigProperty(name = "qits.ci.trigger-deadline-seconds")
  int triggerDeadlineSeconds;

  /**
   * How long an accepted event may sit unevaluated before the periodic sweep treats it as a
   * process's leftovers rather than as work in flight.
   *
   * <p>Generous on purpose: the sweep costs one git-host fan-out per row it picks up, and picking up
   * an evaluation this process is still running is only a duplicate rather than a defect (the dedupe
   * constraint refuses the second run). Wide enough that the ordinary case never happens, short
   * enough that a lost event is recovered in minutes rather than at the next deployment. The boot
   * sweep ignores it entirely — see {@link #onStart}.
   */
  @ConfigProperty(name = "qits.ci.trigger-owed-grace")
  Duration owedGrace;

  /**
   * The repository whose {@code ci-platform-event-*.yml} files are platform pipelines — the wrapper
   * repository by default, because that is the one repository the whole catalogue is described in.
   *
   * <p><b>Blank turns the feature off and reads nothing.</b> A deployment that declares no platform
   * repository must not pay a listing per event for a file it has decided not to have.
   */
  @ConfigProperty(name = "qits.ci.platform-pipelines-repository")
  Optional<String> configuredPlatformPipelinesRepository;

  /**
   * The repository this instance reads platform pipelines from — the config's value, normalised
   * once, and whatever a test armed after that.
   *
   * <p>{@code Optional} above and a plain string here for one reason: a property spelled as the
   * empty string reaches this process as <b>absent</b>, not as {@code ""}, so an unwrapped
   * {@code String} injection point fails the whole deployment on the very value that means "off".
   */
  private String platformPipelinesRepository = "";

  @PostConstruct
  void readPlatformPipelinesRepository() {
    platformPipelinesRepository = normalise(configuredPlatformPipelinesRepository.orElse(""));
  }

  /**
   * Arms the platform-pipelines repository for one test. A method rather than a field write, and
   * that is load-bearing: this bean is normal-scoped, so a test holds a client proxy and a field
   * write would land on the proxy and change nothing.
   */
  void platformPipelinesRepository(String repository) {
    platformPipelinesRepository = normalise(repository);
  }

  private static String normalise(String repository) {
    return repository == null ? "" : repository.trim();
  }

  /**
   * One arriving event, in this module's own vocabulary: plain strings, no bus types. {@code
   * payload} is the canonical JSON qits-events stored, verbatim — it is both what the selection is
   * evaluated against and what reaches the step containers as {@code $QITS_EVENT_PAYLOAD}.
   */
  public record Arrival(String eventId, String eventName, Instant occurredAt, String payload) {}

  /**
   * What one finished evaluation did, which is what a caller who waited for it is owed.
   *
   * @param runIds the runs this evaluation recorded. Every id is a row that exists now.
   * @param repositoriesRead how many candidate repositories answered and were evaluated
   * @param repositoriesSkipped the candidates that did not answer — the git host did not reply, the
   *     repository is gone, it has no {@code main}, or the deadline arrived before its turn. {@code
   *     EventTriggerLookup} cannot tell those apart and neither can this.
   */
  public record Evaluation(
      List<String> runIds, int repositoriesRead, List<String> repositoriesSkipped) {

    public Evaluation {
      runIds = List.copyOf(runIds);
      repositoriesSkipped = List.copyOf(repositoriesSkipped);
    }

    /**
     * Whether the engine got to ask anybody at all. {@code false} means no candidate answered — the
     * git host is unreachable, or qits-ci knows of no repository — so an empty {@link #runIds()}
     * says nothing about the event, and a caller must read it as a failure to evaluate rather than
     * as "nothing matched".
     */
    public boolean answered() {
      return repositoriesRead > 0;
    }
  }

  private final ThreadPoolExecutor evaluator =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          r -> {
            Thread t = new Thread(r, "ci-trigger-worker");
            t.setDaemon(true);
            return t;
          });

  /**
   * How long a shutdown waits for the evaluations it has already accepted.
   *
   * <p>Not the durability mechanism — {@link CiOwedEvent} is — but the difference between a cutover
   * that finishes its work and one that hands it to the successor's boot sweep. An evaluation that
   * completes here still records its {@code QUEUED} rows, and {@code CiRunService} is already
   * draining by then, so those rows are left for the successor to enqueue exactly as an
   * interrupted run's are. A fan-out slower than this is abandoned and its row stays owed, which is
   * the case the ledger exists for.
   */
  private static final int SHUTDOWN_DRAIN_SECONDS = 5;

  @PreDestroy
  void shutdown() {
    evaluator.shutdown();
    try {
      if (evaluator.awaitTermination(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    List<Runnable> abandoned = evaluator.shutdownNow();
    if (!abandoned.isEmpty()) {
      LOG.warnf(
          "%d accepted event(s) were not evaluated before shutdown; their owed rows stand and the"
              + " next sweep re-evaluates them",
          abandoned.size());
    }
  }

  /**
   * The entry the <b>bus listener</b> calls, and only it. <b>Returns immediately and never
   * throws</b>: the caller is a socket callback that is delivering to other consumers too.
   *
   * <p><b>The answer is whether the event was accepted for evaluation</b>, and it is a return value
   * rather than a swallowed WARN because the durable seam can act on it. A full queue means this
   * event was <em>not</em> evaluated, and that is a retryable condition rather than a verdict about
   * the event: {@code CiEventTriggerListener} turns a {@code false} into a failure, which leaves the
   * event owed for the next catch-up sweep instead of dropping it.
   *
   * <p><b>The manual trigger used to come through here too and must never do so again.</b> Its
   * event is on no log and has no claim, so "accepted" is a promise nothing can keep for it — see
   * {@link #evaluateNow} and the class javadoc. Everything that reaches this method has a
   * redelivery channel behind it.
   *
   * <p>A malformed arrival — no id, no name — also answers {@code false}, and it is the caller's job
   * to tell the two apart. The listener does, by checking the frame before it gets here.
   *
   * <p><b>The acceptance is written down before it is reported</b>, which is what makes a {@code
   * true} here worth what the funnel takes it for. A {@link CiOwedEvent} row commits on ci's own
   * datasource first; only then is the event queued and only then is {@code true} answered. An event
   * that could not be recorded is <em>not accepted</em> — {@code false}, so the claim rolls back and
   * the bus offers it again — because an acceptance nothing durable knows about is exactly the
   * promise this method used to break across a cutover.
   */
  public boolean onEvent(Arrival arrival) {
    if (arrival == null || arrival.eventId() == null || arrival.eventName() == null) {
      return false;
    }
    if (!recordOwed(arrival)) {
      return false;
    }
    try {
      evaluator.execute(() -> evaluateQuietly(arrival));
      return true;
    } catch (RejectedExecutionException full) {
      // Either the queue is genuinely backed up or the process is shutting down. Both are worth a
      // line naming the event, because the event is simply not evaluated and nothing else will say so.
      // The owed row goes with the refusal: this event stays the BUS's to redeliver, and leaving the
      // row would make the sweep evaluate what the next offer is going to evaluate anyway.
      settle(arrival.eventId());
      LOG.warnf(
          "Trigger evaluation queue is full — event %s (%s) was not evaluated",
          arrival.eventId(), arrival.eventName());
      return false;
    }
  }

  /**
   * Writes the acceptance down, or answers {@code false} so the caller refuses the event.
   *
   * <p>Its own transaction, because the caller's is the durable funnel's claim on another
   * datasource, and one JTA transaction does not take both ({@code Enlisted connection used without
   * active transaction} — measured; {@code ScmReleaseListener} runs the same arrangement for the same
   * reason). An existing row is success: a redelivery that reached the accept again is owed once, not
   * twice.
   */
  private boolean recordOwed(Arrival arrival) {
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  owed.record(
                      arrival.eventId(),
                      arrival.eventName(),
                      arrival.occurredAt(),
                      arrival.payload()));
      return true;
    } catch (RuntimeException notRecorded) {
      // Loud and refused. Accepting an event this process cannot say it accepted is the failure the
      // ledger exists to prevent, so the honest answer is to leave it owed on the bus instead.
      LOG.errorf(
          notRecorded,
          "Event %s (%s) could not be recorded as owed; refusing it so the bus offers it again",
          arrival.eventId(),
          arrival.eventName());
      return false;
    }
  }

  /**
   * Clears one owed row: this event has been evaluated and nothing is owed for it.
   *
   * <p>A failure here is a WARN and nothing else. The row stays, the next sweep re-evaluates the
   * event, and the dedupe constraint makes that a no-op — an unclearable row costs a fan-out, where
   * throwing would cost the evaluation that already happened.
   */
  private void settle(String eventId) {
    try {
      QuarkusTransaction.requiringNew().run(() -> owed.forget(eventId));
    } catch (RuntimeException notSettled) {
      LOG.warnf(
          "Event %s was evaluated but its owed row could not be cleared; a sweep will re-evaluate"
              + " it, which the dedupe makes a no-op: %s",
          eventId, notSettled.toString());
    }
  }

  /**
   * The entry the manual-trigger endpoint calls: <b>the same evaluation, on this thread</b>, with
   * what it did handed back.
   *
   * <p>It shares no queue and no worker with {@link #onEvent}, which is the whole point — see the
   * class javadoc. It throws whatever the evaluation throws; the caller owes its own caller a
   * non-2xx for that, because an event nobody can redeliver must never be answered "accepted" by a
   * process that did not evaluate it.
   */
  public Evaluation evaluateNow(Arrival arrival) {
    return evaluateNow(arrival, null);
  }

  /**
   * The same evaluation, narrowed to <b>one project's</b> repositories.
   *
   * <p>{@code projectScope} null is every project, which is what an operator's forwarded session and
   * a token granted {@code project=*} get. A non-null value is a machine caller's own project, and
   * the narrowing is the whole of what admits it: the candidates are the ones the catalogue says
   * belong to that project, so a repository of another project is not evaluated, cannot match and
   * cannot be made to run — refused <em>by construction</em> rather than by a check that has to be
   * remembered. See {@link #inProject} for what "the catalogue says" is worth, and {@code
   * CiEventController} for the guard that decides the value.
   *
   * @throws NoRepositoriesInProject when the catalogue holds repositories but none of them is that
   *     project's — a definite refusal for the caller, and never confused with "nothing could be
   *     read", which stays an unanswered evaluation
   */
  public Evaluation evaluateNow(Arrival arrival, String projectScope) {
    return evaluate(
        arrival, System.nanoTime() + SECONDS.toNanos(triggerDeadlineSeconds), projectScope);
  }

  /**
   * A scoped evaluation whose scope names no repository this instance can place in it.
   *
   * <p>A plain {@link RuntimeException} rather than anything web-shaped, for the reason every other
   * type in this module is: the adapter turns it into a status code (403 — the token covers nothing
   * here), and {@code ci} stays free of JAX-RS.
   */
  public static class NoRepositoriesInProject extends RuntimeException {

    private final transient String project;

    NoRepositoriesInProject(String project) {
      super("no repository qits-ci can name belongs to project " + project);
      this.project = project;
    }

    /** The scope that matched nothing — what a caller is told about its own token. */
    public String project() {
      return project;
    }
  }

  private void evaluateQuietly(Arrival arrival) {
    try {
      evaluate(arrival);
    } catch (RuntimeException e) {
      // The owed row is deliberately NOT settled here: a throw is the one outcome a later sweep can
      // improve on, and the dedupe makes re-evaluating whatever did get recorded a no-op.
      LOG.errorf(
          e,
          "Evaluating triggers for event %s failed unexpectedly; it stays owed for the next sweep",
          arrival.eventId());
      return;
    }
    settle(arrival.eventId());
  }

  /**
   * The synchronous evaluation with no deadline — package-private so tests drive it without the
   * executor, and what the trigger worker runs. The worker has all the time it needs: nobody is
   * holding a request open for it, and a candidate it gives up on is a run that never happens.
   */
  Evaluation evaluate(Arrival arrival) {
    return evaluate(arrival, null, null);
  }

  private Evaluation evaluate(Arrival arrival, Long deadlineNanos, String projectScope) {
    JsonNode payload = CiEventSelectionEvaluator.parsePayload(arrival.payload());
    List<CiRepoRef> catalogue = candidateRepos.candidates();
    if (catalogue.isEmpty()) {
      LOG.debugf("No candidate repositories for event %s — nothing to evaluate", arrival.eventName());
      return new Evaluation(List.of(), 0, List.of());
    }
    List<CiRepoRef> candidates = inProject(catalogue, projectScope);
    if (candidates.isEmpty()) {
      // Only reachable with a scope: an empty catalogue answered above. This instance holds
      // repositories and none of them is the caller's, which is an answer about the caller rather
      // than about the git host — so it is a refusal and not an unanswered evaluation.
      throw new NoRepositoriesInProject(projectScope);
    }
    List<String> runIds = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    // The head each candidate answered with, kept for the platform pass: a platform run is recorded
    // against the repository the payload names, at the commit that repository's main was on for THIS
    // evaluation. Reading it again would be a second read of a branch that may have moved.
    Map<String, String> heads = new HashMap<>();
    for (CiRepoRef repo : candidates) {
      if (deadlineNanos != null && System.nanoTime() - deadlineNanos >= 0) {
        // Out of time rather than out of answers, and the two must not look alike to the caller —
        // so the repository goes on the skipped list like any other one that could not be asked.
        skipped.add(repo.repoId());
        continue;
      }
      try {
        if (!evaluateRepo(repo, arrival, payload, runIds, heads)) {
          skipped.add(repo.repoId());
        }
      } catch (RuntimeException e) {
        // One repository's failure never costs the others theirs. Same containment the per-file
        // parse below has, one level up.
        LOG.warnf(e, "Could not evaluate event triggers for %s", repo.display());
        skipped.add(repo.repoId());
      }
    }
    if (projectScope == null) {
      try {
        evaluatePlatform(arrival, payload, candidates, heads, runIds);
      } catch (RuntimeException e) {
        // Never out of the evaluation: the candidates' own runs are already recorded and a platform
        // pipeline's failure is not theirs.
        LOG.warnf(e, "Could not evaluate platform triggers for event %s", arrival.eventId());
      }
    } else {
      // A platform pipeline is ONE repository's file acting on the whole catalogue, so firing one is
      // a platform-wide act and the honest grant for it is every project. A project-scoped caller
      // gets its own repositories' triggers and nothing else. Widening this later is additive; a
      // scope that could reach the platform files would not be.
      LOG.debugf(
          "Event %s was evaluated for project %s only — platform pipelines are not part of a scoped"
              + " evaluation",
          arrival.eventId(), projectScope);
    }
    if (!skipped.isEmpty() && skipped.size() == candidates.size()) {
      // Every candidate silent means the answer is about the git host, not about the event. WARN
      // once for the whole evaluation, where the per-repository lines stay at DEBUG on purpose.
      LOG.warnf(
          "No candidate repository could be read for event %s (%s) — nothing was evaluated",
          arrival.eventId(), arrival.eventName());
    }
    return new Evaluation(runIds, candidates.size() - skipped.size(), skipped);
  }

  /**
   * The candidates one scope covers: everything when the scope is null, and otherwise the ones the
   * catalogue places in that project.
   *
   * <p><b>A candidate whose project qits-ci cannot name is in no scope at all</b>, and that is the
   * fail-closed half of the rule rather than an oversight. Two kinds of candidate have no {@code
   * projectId}: one qits-ci knows only from its own run rows ({@code KnownCiRepos}), and every one of
   * them when the qits-projects listing is unreachable and the git host's id-only listing is all
   * there is. In both cases the honest answer is "this instance cannot prove that repository is
   * yours", so a scoped caller does not reach it — an unreachable listing narrows a scoped
   * evaluation to nothing (and its caller is told so) rather than quietly widening it to everybody.
   * An unscoped evaluation is unaffected: a read failure must never shrink the candidate set.
   */
  private static List<CiRepoRef> inProject(List<CiRepoRef> catalogue, String projectScope) {
    if (projectScope == null) {
      return catalogue;
    }
    List<CiRepoRef> scoped = new ArrayList<>();
    for (CiRepoRef repo : catalogue) {
      if (projectScope.equals(repo.projectId())) {
        scoped.add(repo);
      }
    }
    return scoped;
  }

  /**
   * Evaluates one repository. {@code false} means it could not be read, which is not "no match".
   *
   * <p>The reference travels rather than an id: the trigger files are read name-addressed when the
   * candidate carries a public coordinate, and id-addressed when it does not.
   */
  private boolean evaluateRepo(
      CiRepoRef repo,
      Arrival arrival,
      JsonNode payload,
      List<String> runIds,
      Map<String, String> heads) {
    String repoId = repo.display();
    EventTriggerLookup lookup =
        configSource.readEventTriggers(repo, TRIGGER_BRANCH, CiTriggerScope.REPOSITORY);
    if (lookup.status() != EventTriggerLookup.Status.FOUND) {
      // DEBUG rather than WARN: the candidate list is "every repository ci has ever heard of", so a
      // deleted repository or one with no main would otherwise warn once per repo per event forever.
      LOG.debugf("Could not read %s@%s for triggers", repoId, TRIGGER_BRANCH);
      return false;
    }
    heads.put(repo.repoId(), lookup.headSha());
    for (EventTriggerFile file : lookup.files()) {
      CiEventTrigger trigger;
      try {
        trigger = triggerParser.parse(file.path(), file.content());
      } catch (CiConfigException e) {
        // Loud, naming repository and file — a trigger that cannot be parsed must not silently never
        // fire — and per file: the repository's OTHER trigger files are evaluated regardless.
        LOG.warnf("%s: %s is not a usable event trigger: %s", repoId, file.path(), e.getMessage());
        continue;
      }
      if (!trigger.eventName().equals(arrival.eventName())) {
        continue;
      }
      if (!CiEventSelectionEvaluator.matches(trigger.selection(), payload)) {
        LOG.debugf(
            "%s: %s declares %s but its selection did not match event %s",
            repoId, file.path(), trigger.eventName(), arrival.eventId());
        continue;
      }
      // Absent checkout: today's behavior byte-for-byte — the run builds main's head. Declared,
      // the branch and sha come out of the payload instead; the trigger DECIDED at main above.
      String branch = TRIGGER_BRANCH;
      String sha = lookup.headSha();
      if (trigger.checkout() != null) {
        branch = checkoutField(payload, trigger.checkout().branchPath());
        sha = checkoutField(payload, trigger.checkout().shaPath());
        if (branch == null || sha == null) {
          // One WARN and no run: there is no truthful (branch, sha) pair to record a row against,
          // and a read failure is not a run. Per file — the repository's other triggers still
          // evaluate.
          LOG.warnf(
              "%s: %s declares checkout { %s, %s } but event %s (%s) does not carry both — no run",
              repoId,
              file.path(),
              trigger.checkout().branchPath(),
              trigger.checkout().shaPath(),
              arrival.eventId(),
              arrival.eventName());
          continue;
        }
        // The payload is attacker-shaped (the untrusted-input doctrine): both values reach a clone
        // URL and an argv, so they are validated HERE, inside the per-file containment — letting
        // the refusal escape would trip the per-repo catch and mark the whole repository skipped.
        try {
          CiIdentifiers.requireBranch(branch);
          CiIdentifiers.requireSha(sha);
        } catch (RuntimeException refused) {
          LOG.warnf(
              "%s: %s checkout refused for event %s: %s",
              repoId, file.path(), arrival.eventId(), refused.getMessage());
          continue;
        }
      }
      LOG.infof(
          "Event %s (%s) matched %s in %s — enqueuing a run at %s@%s",
          arrival.eventId(), arrival.eventName(), file.path(), repoId, branch, sha);
      String runId =
          runService.onEventTrigger(
              new CiRunService.EventRun(
                  repo,
                  branch,
                  sha,
                  trigger,
                  arrival.eventId(),
                  arrival.eventName(),
                  arrival.occurredAt(),
                  arrival.payload(),
                  file.content()));
      if (runId != null) {
        runIds.add(runId);
      }
    }
    return true;
  }

  /** A checkout path resolved against the payload; null when the path leads nowhere or to blank. */
  private static String checkoutField(JsonNode payload, String path) {
    JsonNode node = CiEventSelectionEvaluator.resolve(payload, path);
    if (node == null) {
      return null;
    }
    String value = CiEventSelectionEvaluator.asString(node);
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * The platform pass: the files one configured repository declares for the whole catalogue.
   *
   * <p>Read at that repository's {@code main} head, parsed by the same parser and selected by the
   * same grammar as a repository's own trigger — but the run is recorded against, and cloned from,
   * the repository the <b>payload</b> names. That is the whole of the difference, and it is what
   * lets one file bump every repository instead of 71 files bumping one dependency each.
   *
   * <p><b>Three ways it records nothing, and each is one WARN naming the event and the
   * repository.</b> The payload carries no {@code repository}; it names one the catalogue does not
   * hold; or that repository could not be read for this evaluation, so there is no head to record a
   * run at. None of them is a run and none of them is silent: a platform pipeline that matched and
   * then did nothing is exactly the failure a maintenance service cannot see from the outside.
   *
   * <p><b>A repository with both a local and a platform trigger for one event gets two runs.</b>
   * Two files, two declared pipelines, two rows — the dedupe is on {@code (event, repository, config
   * path)} and the two paths differ, so nothing here collapses them. That is by design.
   */
  private void evaluatePlatform(
      Arrival arrival,
      JsonNode payload,
      List<CiRepoRef> candidates,
      Map<String, String> heads,
      List<String> runIds) {
    String configured = platformPipelinesRepository;
    if (configured.isEmpty()) {
      // Off, and off means no read at all.
      return;
    }
    CiRepoRef platformRepo = find(candidates, configured);
    if (platformRepo == null) {
      // WARN rather than DEBUG, unlike the per-candidate reads: this repository is named in this
      // deployment's own config, so a missing one is a misconfiguration that silently disables every
      // platform pipeline, and it can be acted on.
      LOG.warnf(
          "The platform-pipelines repository %s is not in the catalogue — no platform pipeline was"
              + " evaluated for event %s",
          configured, arrival.eventId());
      return;
    }
    EventTriggerLookup lookup =
        configSource.readEventTriggers(platformRepo, TRIGGER_BRANCH, CiTriggerScope.PLATFORM);
    if (lookup.status() != EventTriggerLookup.Status.FOUND) {
      LOG.warnf(
          "Could not read %s@%s for platform triggers — no platform pipeline was evaluated for"
              + " event %s",
          configured, TRIGGER_BRANCH, arrival.eventId());
      return;
    }
    for (EventTriggerFile file : lookup.files()) {
      CiEventTrigger trigger;
      try {
        trigger = triggerParser.parse(file.path(), file.content());
      } catch (CiConfigException e) {
        // Per file, exactly as a repository's own: one broken platform pipeline never disables the
        // ones beside it.
        LOG.warnf(
            "%s: %s is not a usable platform event trigger: %s",
            configured, file.path(), e.getMessage());
        continue;
      }
      if (!trigger.eventName().equals(arrival.eventName())) {
        continue;
      }
      if (!CiEventSelectionEvaluator.matches(trigger.selection(), payload)) {
        LOG.debugf(
            "%s: %s declares %s but its selection did not match event %s",
            configured, file.path(), trigger.eventName(), arrival.eventId());
        continue;
      }
      if (trigger.checkout() != null) {
        // Refused, for now: the platform pass's contract is "the head comes from the candidate
        // pass; no head, no run", and checkout: would build an arbitrary sha of a repository a
        // THIRD repository's file named, with no current use case. Loud, per file, reversible —
        // granting symmetry later is additive.
        LOG.warnf(
            "%s: %s declares 'checkout:', which is not supported in platform pipelines — the run"
                + " builds the named repository's %s head; no run for event %s",
            configured, file.path(), TRIGGER_BRANCH, arrival.eventId());
        continue;
      }
      String named = payloadRepository(payload);
      CiRepoRef target = named == null ? null : find(candidates, named);
      if (target == null) {
        LOG.warnf(
            "Event %s (%s) matched %s in %s, but the repository it names (%s) is not one this"
                + " platform holds — no run",
            arrival.eventId(),
            arrival.eventName(),
            file.path(),
            configured,
            named == null ? "none" : named);
        continue;
      }
      String head = heads.get(target.repoId());
      if (head == null) {
        LOG.warnf(
            "Event %s (%s) matched %s in %s, but %s@%s could not be read — no run",
            arrival.eventId(),
            arrival.eventName(),
            file.path(),
            configured,
            target.display(),
            TRIGGER_BRANCH);
        continue;
      }
      LOG.infof(
          "Event %s (%s) matched platform pipeline %s — enqueuing a run for %s at %s",
          arrival.eventId(), arrival.eventName(), file.path(), target.display(), head);
      String runId =
          runService.onEventTrigger(
              new CiRunService.EventRun(
                  target,
                  TRIGGER_BRANCH,
                  head,
                  trigger,
                  arrival.eventId(),
                  arrival.eventName(),
                  arrival.occurredAt(),
                  arrival.payload(),
                  file.content()));
      if (runId != null) {
        runIds.add(runId);
      }
    }
  }

  /** The payload's {@code repository}, or null when it carries none or carries a non-string. */
  private static String payloadRepository(JsonNode payload) {
    JsonNode value = payload == null ? null : payload.get(PAYLOAD_REPOSITORY_FIELD);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText();
  }

  /**
   * The candidate a name refers to, or null when the catalogue holds none.
   *
   * <p><b>The public name wins over the storage id</b>, and the id arm is the pre-cutover
   * compatibility half: before the identity campaign the two are the same string, after it only the
   * name is something a config key or an event payload could hold.
   */
  private static CiRepoRef find(List<CiRepoRef> candidates, String name) {
    for (CiRepoRef repo : candidates) {
      if (name.equals(repo.name())) {
        return repo;
      }
    }
    for (CiRepoRef repo : candidates) {
      if (name.equals(repo.repoId())) {
        return repo;
      }
    }
    return null;
  }

  // --- the owed-event sweeps: what a process that did not finish left behind -----------------------

  /**
   * Boot: re-evaluate <b>every</b> owed event, whatever its age.
   *
   * <p>No grace, and that is a property of the deployment rather than an optimism. {@code
   * .config/qits/deployments.yml} declares {@code update_order: stop-first} — one CI process at a
   * time — so any row present at boot was accepted by a process that is gone, and waiting to be sure
   * would only make the release request that is missing its QA run wait too.
   *
   * <p><b>On its own thread</b>, {@code ReleaseJoin.onStart}'s rule: a startup observer that blocks
   * on the network loses the container healthcheck's race and cd kills the deployment. This one
   * reads the git host once per candidate per row.
   *
   * <p><b>At {@code CiRunService.BOOT_SWEEP_PRIORITY}</b> rather than unordered, because it can
   * record runs and hand them to the run worker: it must not precede {@code
   * CiDaemonLauncher.onStart}'s container reap, which cannot tell a container this boot started from
   * one the previous life left. Sharing the run sweep's rung is enough for that — the reap's is
   * lower and the observers before it have returned — and the two do not otherwise interact: a run
   * this sweep records is {@code QUEUED}, which the run sweep either re-enqueues or ignores, and
   * both are correct.
   */
  void onStart(@Observes @Priority(CiRunService.BOOT_SWEEP_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread sweeper = new Thread(() -> sweepOwed(Instant.now()), "ci-trigger-owed-sweep");
    sweeper.setDaemon(true);
    sweeper.start();
  }

  /**
   * The schedule underneath the boot pass, and it is not redundant with it.
   *
   * <p>A boot sweep is one attempt: a database that was not there yet when it ran, or a git host
   * that answered nothing, leaves the events owed until the next deployment — which for a service
   * whose release train rides those events is exactly the outage the ledger was written to end. The
   * tick makes the recovery self-healing instead.
   *
   * <p>{@link Scheduled.ConcurrentExecution#SKIP} because a sweep is a fan-out and two of them are
   * one storm at the git host; the grace is what keeps it from re-offering work this process is
   * still doing.
   */
  @Scheduled(
      every = "{qits.ci.trigger-owed-sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void sweepOwedTick() {
    sweepOwed(Instant.now().minus(owedGrace));
  }

  /**
   * Re-evaluates every event accepted before {@code cutoff} and never evaluated — package-private
   * because both callers above skip or defer in a suite, so this is what a test drives to make a
   * claim about a lost event.
   *
   * <p>One row at a time on this thread, and per-row containment: a row that throws stays owed for
   * the next sweep, and the ones behind it are still swept. A row whose evaluation <em>returned</em>
   * is settled even if no repository could be read — see the class javadoc for why that is not a
   * retry this ledger owes.
   */
  void sweepOwed(Instant cutoff) {
    List<CiOwedEvent> stale;
    try {
      stale = QuarkusTransaction.requiringNew().call(() -> owed.listAcceptedBefore(cutoff));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the owed trigger events");
      return;
    }
    if (stale.isEmpty()) {
      return;
    }
    LOG.infof(
        "Re-evaluating %d event(s) accepted by a process that did not finish with them", stale.size());
    int recovered = 0;
    for (CiOwedEvent row : stale) {
      Arrival arrival = new Arrival(row.eventId, row.eventName, row.occurredAt, row.payload);
      try {
        Evaluation done = evaluate(arrival);
        settle(row.eventId);
        recovered++;
        if (!done.runIds().isEmpty()) {
          LOG.infof(
              "Event %s (%s) was owed and is now evaluated: %d run(s) recorded",
              row.eventId, row.eventName, done.runIds().size());
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            e, "Could not re-evaluate owed event %s (%s); it stays owed", row.eventId, row.eventName);
      }
    }
    LOG.infof("Owed-event sweep: %d of %d re-evaluated", recovered, stale.size());
  }

  /** Test hook: waits for the evaluation queued at this moment to drain. */
  void awaitIdle() throws Exception {
    evaluator.submit(() -> {}).get();
  }
}
