package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.error.ConflictException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A queued run is a row.</b> It is written when the work is accepted rather than when the worker
 * reaches it, so it is visible while it waits and it survives the process that accepted it — which
 * is the whole of what {@link CiRunStatus#QUEUED} bought and the reason a redeploy no longer eats
 * accepted builds.
 *
 * <p>Every claim here is staged against a <b>genuinely occupied worker</b> rather than against a
 * sleep. The worker is single-threaded, so holding one run inside its first step is exactly what
 * makes the next one queue — and it makes "queued" an instant the test controls rather than a window
 * it hopes to catch. {@code CiRunServiceTest} owns the rest of the state machine; this class owns
 * the queue, the restart and the cancellation that arrives before a run starts.
 */
@QuarkusTest
public class CiQueuedRunTest extends CiTestSupport {

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo one
      """;

  @Inject CiRunService service;

  /** Opened by the test, awaited on the worker inside the blocking run's first step. */
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void releaseTheWorker() throws Exception {
    // The flag outlives a test method — one CDI instance serves the whole suite — so it is put back
    // down here rather than in the two methods that raise it.
    service.draining(false);
    release.countDown();
    service.awaitIdle();
  }

  /** A fresh repository id; the sha is derived from it so a test can name both with one string. */
  private String seedRepo() {
    return "queued-" + UUID.randomUUID();
  }

  /** A valid 40-character hex sha derived from the repository id, so a test carries one string. */
  private static String shaOf(String repoId) {
    return String.format("%08x", repoId.hashCode()).repeat(5);
  }

  /**
   * Accepts a run that parks inside its first step until {@link #release}, and returns once the
   * worker is really inside it. Everything accepted after this call is genuinely queued.
   */
  private String occupyTheWorker() throws Exception {
    String repoId = seedRepo();
    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    fakeRunner.during(
        0,
        spec -> {
          inStepZero.complete(spec.runId());
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    accept(repoId);
    return inStepZero.get(20, TimeUnit.SECONDS);
  }

  /**
   * One trigger accepted: the row is written and the worker is handed it. A fresh event id per call
   * (see {@code CiTestSupport.eventRun}), because reusing one would be a second run for one
   * announcement, which the unique constraint on {@code (trigger_event_id, repo_id, config_path)}
   * refuses — correctly.
   */
  private void accept(String repoId) {
    service.onEventTrigger(eventRun(repoId, "main", shaOf(repoId), CONFIG_ONE_STEP));
  }

  private CiRun soleRun(String repoId) {
    forgetLoadedEntities();
    List<CiRun> all = service.runsFor(repoId);
    assertEquals(1, all.size(), "expected exactly one recorded run for " + repoId);
    return all.get(0);
  }

  // --- the lifecycle ---

  @Test
  public void theAcceptWritesTheRowBeforeItReturnsAndTheWorkerFlipsItToRunning() throws Exception {
    String blockingRunId = occupyTheWorker();
    String repoId = seedRepo();

    accept(repoId);

    // Accepted, on the record, and not started: the whole point. Before this status existed the
    // only trace of this run was a closure on an executor.
    CiRun queued = soleRun(repoId);
    assertEquals(CiRunStatus.QUEUED, queued.status);
    assertNull(queued.finishedAt, "a queued run has not finished");
    assertNull(queued.daemonVersion, "nothing is pinned until a container is about to launch");
    assertEquals(CiTriggerType.EVENT, queued.triggerType);
    assertEquals(0, service.stepsFor(queued.id).size());

    // The run holding the worker is RUNNING at the same instant, which is what makes the two states
    // distinguishable rather than a naming choice.
    forgetLoadedEntities();
    assertEquals(CiRunStatus.RUNNING, service.requireRun(blockingRunId).status);

    release.countDown();
    service.awaitIdle();

    CiRun finished = soleRun(repoId);
    assertEquals(CiRunStatus.SUCCESS, finished.status);
    assertEquals(queued.id, finished.id, "the accepted row is the row that ran — never a second one");
    assertNotNull(finished.finishedAt);
    assertEquals("fake-daemon", finished.daemonVersion, "the pin lands on the accepted row");
    assertEquals(1, service.stepsFor(finished.id).size());
  }

  @Test
  public void theActiveListingIsEveryQueuedAndRunningRunAcrossRepositories() throws Exception {
    String blockingRunId = occupyTheWorker();
    String waiting = seedRepo();
    accept(waiting);
    forgetLoadedEntities();

    List<CiRun> active = service.activeRuns();
    assertEquals(2, active.size(), "one running, one queued — and they are in different repositories");
    // Newest first, and both states are present.
    assertEquals(waiting, active.get(0).repoId);
    assertEquals(CiRunStatus.QUEUED, active.get(0).status);
    assertEquals(blockingRunId, active.get(1).id);
    assertEquals(CiRunStatus.RUNNING, active.get(1).status);

    release.countDown();
    service.awaitIdle();
    forgetLoadedEntities();

    // Terminal runs leave it, so an empty answer means "CI is idle" rather than "nothing ever ran".
    assertEquals(List.of(), service.activeRuns());
  }

  @Test
  public void bothNewReadsAnswerEmptyOnAnInstanceThatHasRecordedNothing() {
    // The empty state is a real answer here rather than an edge case: a fresh deployment serves it
    // on every page load until the first run, and it must be an empty list rather than a null or a
    // 404. The suite wipes both tables per test, so this is that instance.
    assertEquals(List.of(), service.activeRuns());
    assertEquals(List.of(), service.repositorySummaries());
    assertEquals(List.of(), service.repositoryIds());
  }

  @Test
  public void aRepositorySummaryNamesTheNewestRunAndTheNewestMainRunSeparately() throws Exception {
    // The two questions the summary answers are genuinely different, and the second one's answer can
    // be arbitrarily far down the list — which is why it is its own query rather than a filter over
    // the first one's answer.
    String repoId = "summary-" + UUID.randomUUID();
    String mainSha = "1".repeat(40);
    String featureSha = "2".repeat(40);

    executePipeline(repoId, "main", mainSha, CONFIG_ONE_STEP);
    executePipeline(repoId, "feature", featureSha, CONFIG_ONE_STEP);
    forgetLoadedEntities();

    List<CiRunService.RepositorySummary> summaries = service.repositorySummaries();
    assertEquals(1, summaries.size());
    CiRunService.RepositorySummary summary = summaries.get(0);
    assertEquals(repoId, summary.repositoryId());
    assertEquals("feature", summary.lastRun().branch, "lastRun is the newest on any branch");
    assertEquals("main", summary.lastMainRun().branch);
    assertEquals(mainSha, summary.lastMainRun().commitSha);

    // A repository whose only run is on another branch has no main run at all — a null rather than a
    // fallback to its newest run, which would answer "is main green" with something that is not main.
    String featureOnly = "featureonly-" + UUID.randomUUID();
    executePipeline(featureOnly, "feature", featureSha, CONFIG_ONE_STEP);
    forgetLoadedEntities();

    CiRunService.RepositorySummary noMain =
        service.repositorySummaries().stream()
            .filter(s -> s.repositoryId().equals(featureOnly))
            .findFirst()
            .orElseThrow();
    assertNotNull(noMain.lastRun());
    assertNull(noMain.lastMainRun(), "never run on main is a null, not a substitute");
  }

  // --- the cancellation that arrives before the run starts ---

  @Test
  public void aRunCancelledWhileQueuedIsCancelledAndNeverPickedUp() throws Exception {
    occupyTheWorker();
    String repoId = seedRepo();
    accept(repoId);
    String queuedId = soleRun(repoId).id;
    int launchedBefore = fakeRunner.executed().size();

    service.cancel(queuedId);

    // Terminal immediately: there is no container to ask and no step to stop, so the row is the
    // whole of the cancellation.
    CiRun cancelled = soleRun(repoId);
    assertEquals(CiRunStatus.CANCELLED, cancelled.status);
    assertNull(cancelled.startedAt, "a run cancelled in the queue never started");
    assertNotNull(cancelled.finishedAt);
    assertEquals(0, service.stepsFor(queuedId).size(), "a run that never started has no steps");
    // Nothing was asked to die, because nothing was launched — unlike a mid-step cancellation.
    assertFalse(fakeRunner.cancelled().contains(queuedId));

    release.countDown();
    service.awaitIdle();

    // And the worker really did skip it rather than running it anyway and overwriting the row.
    CiRun afterTheQueueDrained = soleRun(repoId);
    assertEquals(CiRunStatus.CANCELLED, afterTheQueueDrained.status);
    assertEquals(cancelled.finishedAt, afterTheQueueDrained.finishedAt, "the row was not rewritten");
    assertEquals(
        launchedBefore, fakeRunner.executed().size(), "the cancelled run must launch no container");
    // A second cancellation has nothing left to stop.
    assertThrows(ConflictException.class, () -> service.cancel(queuedId));
  }

  // --- the shutdown that must claim nothing ---

  @Test
  public void aDrainingServiceLeavesAQueuedRunQueuedForTheSuccessorToPickUp() throws Exception {
    // The 2026-08-23 incident, staged: a start-first successor had already swept when the dying
    // process claimed a queued row and died holding it RUNNING — past every sweep, owned by nobody.
    // A draining process must hand its backlog on instead.
    occupyTheWorker();
    String repoId = seedRepo();
    accept(repoId);
    String queuedId = soleRun(repoId).id;
    int launchedBefore = fakeRunner.executed().size();

    service.draining(true);
    release.countDown();
    service.awaitIdle();

    CiRun stillQueued = soleRun(repoId);
    assertEquals(queuedId, stillQueued.id);
    assertEquals(CiRunStatus.QUEUED, stillQueued.status, "a draining process must claim nothing");
    assertNull(stillQueued.startedAt, "the row was never flipped to RUNNING");
    assertNull(stillQueued.finishedAt, "and it was not finished either — it is the successor's");
    assertEquals(
        launchedBefore, fakeRunner.executed().size(), "no container for a run nobody claimed");

    // And the sweep a successor runs picks exactly this row back up.
    service.draining(false);
    service.sweepInterrupted();
    service.awaitIdle();
    assertEquals(CiRunStatus.SUCCESS, soleRun(repoId).status);
  }

  @Test
  public void aDrainingServiceAcceptsARunAndPutsItOnNoWorker() throws Exception {
    // The other half of the guard: the row is still written, because losing accepted work is the
    // failure QUEUED exists to close. It simply never reaches this process's worker.
    occupyTheWorker();
    String repoId = seedRepo();
    service.draining(true);

    accept(repoId);

    CiRun accepted = soleRun(repoId);
    assertEquals(CiRunStatus.QUEUED, accepted.status);
    release.countDown();
    service.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.QUEUED, soleRun(repoId).status, "the worker was never handed it");
  }

  // --- the dedupe, which now fires at accept ---

  @Test
  public void aRedeliveredEventIsDroppedAtAcceptAndNeverReachesTheQueue() throws Exception {
    occupyTheWorker();
    String repoId = "consumer-" + UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();

    service.onEventTrigger(eventRun(repoId, eventId));
    service.onEventTrigger(eventRun(repoId, eventId));

    // One row, asserted while the worker is still blocked — so this is the insert refusing the
    // duplicate, not a later run overwriting an earlier one.
    CiRun run = soleRun(repoId);
    assertEquals(CiRunStatus.QUEUED, run.status);
    assertEquals(eventId, run.triggerEventId);
    assertEquals(CiTriggerType.EVENT, run.triggerType);

    release.countDown();
    service.awaitIdle();
    assertEquals(CiRunStatus.SUCCESS, soleRun(repoId).status);
  }

  // --- gating, as data on the row ---

  @Test
  public void aTriggersGatingFlagLandsOnTheRowAndDefaultsToGating() throws Exception {
    // The userflows case: a trigger saying `gating: false` records a run whose red outcome must not
    // stand in the way of releasing the commit. A file that says nothing is gating, which is the
    // safe direction and the one every ordinary pipeline relies on.
    String eventRepo = "consumer-" + UUID.randomUUID();
    service.onEventTrigger(eventRun(eventRepo, UUID.randomUUID().toString(), false));
    service.awaitIdle();
    assertFalse(soleRun(eventRepo).gating, "the trigger said gating: false");

    String silentRepo = seedRepo();
    accept(silentRepo);
    service.awaitIdle();
    assertTrue(soleRun(silentRepo).gating, "a file that declares nothing is gating");
  }

  private CiRunService.EventRun eventRun(String repoId, String eventId) {
    return eventRun(repoId, eventId, true);
  }

  private CiRunService.EventRun eventRun(String repoId, String eventId, boolean gating) {
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId),
        "main",
        "c".repeat(40),
        new CiEventTrigger(
            ".config/qits/ci-event-upstream.yml",
            "BuildSuccessful",
            null, // the selection already matched; nothing below this seam reads it
            new CiPipeline(
                List.of(
                    new CiPipeline.CiStepDecl(
                        "alpine:3", "echo bump", null, false, "", true))),
            List.of(), // declares no artifact: this run announces a build and nothing more
            gating,
            null), // no checkout: builds main's head, as every trigger did before the key
        eventId,
        "BuildSuccessful",
        Instant.parse("2026-07-31T12:46:03Z"),
        "{}",
        """
        event: BuildSuccessful
        steps:
          - image: alpine:3
            script: echo bump
        """);
  }

  // --- the restart ---

  @Test
  public void aRestartRestartsWhatItCanAndSettlesWhatItCannot() throws Exception {
    // The sweep is what onStart calls; onStart itself is skipped in test mode, so this drives it
    // directly. Five rows, four different answers.
    //
    // The two POST_RECEIVE rows are what a PREDECESSOR left: the push intake retired on 2026-09-05,
    // so no live deployment writes one, and a successor still has to say something honest about the
    // ones already in its database. A RUNNING one is failed (its step died with its process and this
    // engine could not replay repository-authored work anyway); a QUEUED one is settled CANCELLED,
    // because leaving it queued would park it in /ci/api/runs/active forever waiting for a worker
    // that no longer exists — the phantom this whole retirement is about.
    String interruptedRepo = "swept-run-" + UUID.randomUUID();
    String interrupted =
        insertRow(
            interruptedRepo,
            "f".repeat(40),
            CiRunStatus.RUNNING,
            CiTriggerType.POST_RECEIVE,
            Instant.now());
    String strandedPushRepo = "swept-stranded-push-" + UUID.randomUUID();
    String strandedPush =
        insertRow(
            strandedPushRepo,
            "d".repeat(40),
            CiRunStatus.QUEUED,
            CiTriggerType.POST_RECEIVE,
            Instant.now());
    String repoId = seedRepo();
    String requeued = insertQueuedEventRun(repoId);
    String eventRepo = "swept-evt-" + UUID.randomUUID();
    insertRow(eventRepo, "f".repeat(40), CiRunStatus.QUEUED, CiTriggerType.EVENT, Instant.now());
    String runningEventRepo = "swept-running-evt-" + UUID.randomUUID();
    String runningEvent =
        insertRow(
            runningEventRepo,
            "e".repeat(40),
            CiRunStatus.RUNNING,
            CiTriggerType.EVENT,
            Instant.now());
    insertStaleStep(runningEvent);

    service.sweepInterrupted();
    service.awaitIdle();
    forgetLoadedEntities();

    // Its in-flight step died with the process — no re-enqueue could be honest about that.
    CiRun failed = service.requireRun(interrupted);
    assertEquals(CiRunStatus.FAILED, failed.status);
    assertNotNull(failed.finishedAt);

    // And the queued push leftover is settled rather than left sitting in the active listing.
    CiRun stranded = service.requireRun(strandedPush);
    assertEquals(CiRunStatus.CANCELLED, stranded.status, "no engine here can run a push row");
    assertEquals(
        CiRunService.TRIGGER_RETIRED,
        stranded.cancellationReason,
        "its own reason: nobody cancelled it, the engine that would have run it is gone");
    assertNotNull(stranded.finishedAt);
    assertNull(stranded.startedAt, "it was never claimed");
    assertEquals(0, service.stepsFor(strandedPush).size());
    assertTrue(
        fakeRunner.executed().stream()
            .noneMatch(spec -> spec.repo().repoId().equals(strandedPushRepo)),
        "and no container was launched for it");

    // It never started, and its row says everything needed to start it: this is the cutover loss,
    // closed.
    CiRun ran = service.requireRun(requeued);
    assertEquals(CiRunStatus.SUCCESS, ran.status);
    assertEquals(1, service.stepsFor(requeued).size(), "the re-enqueued run really executed");

    // Its event envelope and exact trigger file are durable too. Recovery reparses that snapshot
    // and preserves the environment instead of waiting for an at-most-once event redelivery.
    CiRun eventRan = service.runsFor(eventRepo).get(0);
    assertEquals(CiRunStatus.SUCCESS, eventRan.status);
    CiStepRunner.StepSpec recoveredEvent =
        fakeRunner.executed().stream()
            .filter(spec -> spec.repo().repoId().equals(eventRepo))
            .findFirst()
            .orElseThrow();
    assertEquals("{\"version\":\"2026.802.154030\"}", recoveredEvent.env().get("QITS_EVENT_PAYLOAD"));
    assertEquals("2026-08-02T15:40:31Z", recoveredEvent.env().get("QITS_EVENT_OCCURRED_AT"));

    CiRun restarted = service.requireRun(runningEvent);
    assertEquals(CiRunStatus.SUCCESS, restarted.status);
    assertEquals("fake-daemon", restarted.daemonVersion, "the dead process's daemon pin was reset");
    assertEquals(1, service.stepsFor(runningEvent).size(), "partial prior-attempt steps were cleared");
    assertEquals(
        1,
        fakeRunner.executed().stream()
            .filter(spec -> spec.repo().repoId().equals(runningEventRepo))
            .count(),
        "one startup sweep restarts the interrupted event once");

    service.sweepInterrupted();
    service.awaitIdle();
    assertEquals(
        1,
        fakeRunner.executed().stream()
            .filter(spec -> spec.repo().repoId().equals(runningEventRepo))
            .count(),
        "a completed recovered run is idempotent across later startup sweeps");
  }

  @Test
  public void theSweepRePutsQueuedRunsBackInTheOrderTheyWereAccepted() throws Exception {
    // A restart must not reorder a backlog: the worker is FIFO and createdAt is what says what FIFO
    // meant before the process died.
    String first = seedRepo();
    String second = seedRepo();
    String third = seedRepo();
    insertQueuedEventRun(third, Instant.now().minusSeconds(10));
    insertQueuedEventRun(first, Instant.now().minusSeconds(30));
    insertQueuedEventRun(second, Instant.now().minusSeconds(20));

    service.sweepInterrupted();
    service.awaitIdle();

    assertEquals(
        List.of(first, second, third),
        fakeRunner.executed().stream().map(spec -> spec.repo().repoId()).toList());
  }

  // --- rows a previous process would have left behind ---

  private String insertQueuedEventRun(String repoId) {
    return insertQueuedEventRun(repoId, Instant.now());
  }

  private String insertQueuedEventRun(String repoId, Instant createdAt) {
    return insertRow(repoId, shaOf(repoId), CiRunStatus.QUEUED, CiTriggerType.EVENT, createdAt);
  }

  private void insertStaleStep(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiStep step = new CiStep();
              step.id = UUID.randomUUID().toString();
              step.runId = runId;
              step.stepIndex = 0;
              step.image = "dead-attempt:latest";
              step.status = CiStepStatus.SUCCESS;
              step.exitCode = 0;
              step.startedAt = Instant.now().minusSeconds(2);
              step.finishedAt = Instant.now().minusSeconds(1);
              step.output = "partial attempt";
              steps.persist(step);
            });
  }

  private String insertRow(
      String repoId,
      String sha,
      CiRunStatus status,
      CiTriggerType triggerType,
      Instant createdAt) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = id;
              run.repoId = repoId;
              run.branch = "main";
              run.commitSha = sha;
              run.status = status;
              run.createdAt = createdAt;
              run.triggerType = triggerType;
              if (status == CiRunStatus.RUNNING) {
                run.daemonVersion = "dead-daemon";
              }
              // The push arm's constant as a literal: it retired with the intake, and what a
              // predecessor's row carries is the string rather than anything live code still names.
              run.configPath =
                  triggerType == CiTriggerType.POST_RECEIVE
                      ? ".config/qits/ci-post-receive.yml"
                      : ".config/qits/ci-event-upstream.yml";
              if (triggerType == CiTriggerType.EVENT) {
                run.triggerEventId = UUID.randomUUID().toString();
                run.triggerEventName = "BuildSuccessful";
                run.triggerEventOccurredAt = Instant.parse("2026-08-02T15:40:31Z");
                run.triggerEventPayload = "{\"version\":\"2026.802.154030\"}";
                run.triggerConfig =
                    """
                    event: BuildSuccessful
                    steps:
                      - image: alpine:3
                        script: echo recovered
                    """;
              }
              runs.persist(run);
            });
    return id;
  }
}
