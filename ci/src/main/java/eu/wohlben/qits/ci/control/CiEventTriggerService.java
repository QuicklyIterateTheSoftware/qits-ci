package eu.wohlben.qits.ci.control;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 */
@ApplicationScoped
public class CiEventTriggerService {

  private static final Logger LOG = Logger.getLogger(CiEventTriggerService.class);

  /**
   * The branch an event trigger reads. The platform's one tracked branch — every submodule follows
   * it — and it has to be supplied by convention because, unlike a push, an event names no ref.
   */
  public static final String TRIGGER_BRANCH = "main";

  /** Deep enough that reaching it means something is wrong rather than something is busy. */
  static final int QUEUE_CAPACITY = 256;

  @Inject CiConfigSource configSource;
  @Inject CiEventTriggerParser triggerParser;
  @Inject CiCandidateRepos candidateRepos;
  @Inject CiRunService runService;

  /**
   * How long {@link #evaluateNow} keeps asking repositories. It runs on a request thread and reads
   * the git host once per candidate, so an unanswering host would otherwise hold the caller for
   * candidates times the read timeout. Past the deadline the repositories not yet asked are reported
   * as skipped, which is the truth about them.
   */
  @ConfigProperty(name = "qits.ci.trigger-deadline-seconds")
  int triggerDeadlineSeconds;

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

  @PreDestroy
  void shutdown() {
    evaluator.shutdownNow();
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
   */
  public boolean onEvent(Arrival arrival) {
    if (arrival == null || arrival.eventId() == null || arrival.eventName() == null) {
      return false;
    }
    try {
      evaluator.execute(() -> evaluateQuietly(arrival));
      return true;
    } catch (RejectedExecutionException full) {
      // Either the queue is genuinely backed up or the process is shutting down. Both are worth a
      // line naming the event, because the event is simply not evaluated and nothing else will say so.
      LOG.warnf(
          "Trigger evaluation queue is full — event %s (%s) was not evaluated",
          arrival.eventId(), arrival.eventName());
      return false;
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
    return evaluate(arrival, System.nanoTime() + SECONDS.toNanos(triggerDeadlineSeconds));
  }

  private void evaluateQuietly(Arrival arrival) {
    try {
      evaluate(arrival);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Evaluating triggers for event %s failed unexpectedly", arrival.eventId());
    }
  }

  /**
   * The synchronous evaluation with no deadline — package-private so tests drive it without the
   * executor, and what the trigger worker runs. The worker has all the time it needs: nobody is
   * holding a request open for it, and a candidate it gives up on is a run that never happens.
   */
  Evaluation evaluate(Arrival arrival) {
    return evaluate(arrival, null);
  }

  private Evaluation evaluate(Arrival arrival, Long deadlineNanos) {
    JsonNode payload = CiEventSelectionEvaluator.parsePayload(arrival.payload());
    List<CiRepoRef> candidates = candidateRepos.candidates();
    if (candidates.isEmpty()) {
      LOG.debugf("No candidate repositories for event %s — nothing to evaluate", arrival.eventName());
      return new Evaluation(List.of(), 0, List.of());
    }
    List<String> runIds = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (CiRepoRef repo : candidates) {
      if (deadlineNanos != null && System.nanoTime() - deadlineNanos >= 0) {
        // Out of time rather than out of answers, and the two must not look alike to the caller —
        // so the repository goes on the skipped list like any other one that could not be asked.
        skipped.add(repo.repoId());
        continue;
      }
      try {
        if (!evaluateRepo(repo, arrival, payload, runIds)) {
          skipped.add(repo.repoId());
        }
      } catch (RuntimeException e) {
        // One repository's failure never costs the others theirs. Same containment the per-file
        // parse below has, one level up.
        LOG.warnf(e, "Could not evaluate event triggers for %s", repo.display());
        skipped.add(repo.repoId());
      }
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
   * Evaluates one repository. {@code false} means it could not be read, which is not "no match".
   *
   * <p>The reference travels rather than an id: the trigger files are read name-addressed when the
   * candidate carries a public coordinate, and id-addressed when it does not.
   */
  private boolean evaluateRepo(
      CiRepoRef repo, Arrival arrival, JsonNode payload, List<String> runIds) {
    String repoId = repo.display();
    EventTriggerLookup lookup = configSource.readEventTriggers(repo, TRIGGER_BRANCH);
    if (lookup.status() != EventTriggerLookup.Status.FOUND) {
      // DEBUG rather than WARN: the candidate list is "every repository ci has ever heard of", so a
      // deleted repository or one with no main would otherwise warn once per repo per event forever.
      LOG.debugf("Could not read %s@%s for triggers", repoId, TRIGGER_BRANCH);
      return false;
    }
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
      LOG.infof(
          "Event %s (%s) matched %s in %s — enqueuing a run at %s",
          arrival.eventId(), arrival.eventName(), file.path(), repoId, lookup.headSha());
      String runId =
          runService.onEventTrigger(
              new CiRunService.EventRun(
                  repo,
                  TRIGGER_BRANCH,
                  lookup.headSha(),
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

  /** Test hook: waits for the evaluation queued at this moment to drain. */
  void awaitIdle() throws Exception {
    evaluator.submit(() -> {}).get();
  }
}
