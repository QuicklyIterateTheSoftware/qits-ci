package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
   * One arriving event, in this module's own vocabulary: plain strings, no bus types. {@code
   * payload} is the canonical JSON qits-events stored, verbatim — it is both what the selection is
   * evaluated against and what reaches the step containers as {@code $QITS_EVENT_PAYLOAD}.
   */
  public record Arrival(String eventId, String eventName, Instant occurredAt, String payload) {}

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
   * The entry the bus listener and the manual-trigger endpoint call. <b>Returns immediately and
   * never throws</b>: one caller is a socket callback that is delivering to other consumers too, the
   * other is a request thread that has already promised a 202.
   *
   * <p><b>The answer is whether the event was accepted for evaluation</b>, and it is a return value
   * rather than a swallowed WARN because the durable seam can now act on it. A full queue means this
   * event was <em>not</em> evaluated, and that is a retryable condition rather than a verdict about
   * the event: {@code CiEventTriggerListener} turns a {@code false} into a failure, which leaves the
   * event owed for the next catch-up sweep instead of dropping it. The WARN stays for the caller
   * that cannot retry.
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

  private void evaluateQuietly(Arrival arrival) {
    try {
      evaluate(arrival);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Evaluating triggers for event %s failed unexpectedly", arrival.eventId());
    }
  }

  /** The synchronous evaluation — package-private so tests drive it without the executor. */
  void evaluate(Arrival arrival) {
    JsonNode payload = CiEventSelectionEvaluator.parsePayload(arrival.payload());
    Set<String> candidates = candidateRepos.candidates();
    if (candidates.isEmpty()) {
      LOG.debugf("No candidate repositories for event %s — nothing to evaluate", arrival.eventName());
      return;
    }
    for (String repoId : candidates) {
      try {
        evaluateRepo(repoId, arrival, payload);
      } catch (RuntimeException e) {
        // One repository's failure never costs the others theirs. Same containment the per-file
        // parse below has, one level up.
        LOG.warnf(e, "Could not evaluate event triggers for %s", repoId);
      }
    }
  }

  private void evaluateRepo(String repoId, Arrival arrival, JsonNode payload) {
    EventTriggerLookup lookup = configSource.readEventTriggers(repoId, TRIGGER_BRANCH);
    if (lookup.status() != EventTriggerLookup.Status.FOUND) {
      // DEBUG rather than WARN: the candidate list is "every repository ci has ever heard of", so a
      // deleted repository or one with no main would otherwise warn once per repo per event forever.
      LOG.debugf("Could not read %s@%s for triggers", repoId, TRIGGER_BRANCH);
      return;
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
      runService.onEventTrigger(
          new CiRunService.EventRun(
              repoId,
              TRIGGER_BRANCH,
              lookup.headSha(),
              trigger,
              arrival.eventId(),
              arrival.eventName(),
              arrival.occurredAt(),
              arrival.payload(),
              file.content()));
    }
  }

  /** Test hook: waits for the evaluation queued at this moment to drain. */
  void awaitIdle() throws Exception {
    evaluator.submit(() -> {}).get();
  }
}
