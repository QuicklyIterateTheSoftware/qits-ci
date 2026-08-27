package eu.wohlben.qits.ci.control;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

  /**
   * How long {@link #evaluateNow} keeps asking repositories. It runs on a request thread and reads
   * the git host once per candidate, so an unanswering host would otherwise hold the caller for
   * candidates times the read timeout. Past the deadline the repositories not yet asked are reported
   * as skipped, which is the truth about them.
   */
  @ConfigProperty(name = "qits.ci.trigger-deadline-seconds")
  int triggerDeadlineSeconds;

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
   * Arms the platform-pipelines repository for one test. A method rather than a field write for
   * {@code CiRunService.unreachableRetryDelays}' reason: this bean is normal-scoped, so a test holds
   * a client proxy and a field write would land on the proxy and change nothing.
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
    try {
      evaluatePlatform(arrival, payload, candidates, heads, runIds);
    } catch (RuntimeException e) {
      // Never out of the evaluation: the candidates' own runs are already recorded and a platform
      // pipeline's failure is not theirs.
      LOG.warnf(e, "Could not evaluate platform triggers for event %s", arrival.eventId());
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

  /** Test hook: waits for the evaluation queued at this moment to drain. */
  void awaitIdle() throws Exception {
    evaluator.submit(() -> {}).get();
  }
}
