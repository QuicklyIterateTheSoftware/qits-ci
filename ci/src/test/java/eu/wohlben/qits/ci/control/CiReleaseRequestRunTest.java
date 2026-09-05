package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release-request QA pipeline, end to end: a {@code ReleaseRequestChanged} builds the fold it
 * names, the run records which request it serves, and one file carries a gating half and a
 * non-gating half whose failures are worth different things to a release gate.
 *
 * <p><b>The trigger needs no engine knowledge and this file is where that is pinned.</b> {@code
 * event:} is matched against the frame's name as a string and {@code checkout:} resolves two dot
 * paths out of the payload, so a release-request pipeline is the existing grammar pointed at a new
 * event — "decide at main, build at the merged sha" comes out unchanged. What IS event-specific is
 * exactly one provenance column, and the two strings behind it are guarded by {@code
 * bus/ReleaseRequestChangedContractTest} in the service module.
 */
@QuarkusTest
public class CiReleaseRequestRunTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  /** The reference file's shape: gating build first, non-gating publish after. */
  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      when:
        - repoName: { exact: qits-ci-service }
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: qits/build-images/maven-base:latest
          script: ./mvnw verify
        - image: qits/build-images/maven-base:latest
          gating: false
          script: ./publish-userflows.sh
      """;

  private static final String HEAD = "a".repeat(40);
  private static final String MERGED = "b".repeat(40);
  private static final String REQUEST_ID = "rr-42";

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private String repoId;

  @BeforeEach
  void resetTriggerState() {
    repoId = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(CiRepoRef.of(repoId, "qits", "qits-ci-service"));
    announcer.reset();
  }

  // --- the trigger -------------------------------------------------------------------------------

  @Test
  public void aReleaseRequestChangedBuildsTheFoldAndRecordsTheRequestItServes() throws Exception {
    seedQa();
    String eventId = UUID.randomUUID().toString();

    deliver(arrival(eventId, payload(REQUEST_ID, MERGED)));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "one QA pipeline is one run");
    CiRun run = recorded.get(0);
    assertEquals(CiTriggerType.EVENT, run.triggerType);
    assertEquals(QA_PATH, run.configPath);
    assertEquals(eventId, run.triggerEventId);
    assertEquals("ReleaseRequestChanged", run.triggerEventName);
    // Decided at main, built at the fold: the trigger file was READ at main's head and the row
    // names the branch and sha the payload carried.
    assertTrue(
        fakeConfig.triggerReads().stream().anyMatch(read -> read.contains("@main#")),
        "the trigger file is discovered at main, never at the fold: " + fakeConfig.triggerReads());
    assertEquals("release/" + REQUEST_ID, run.branch);
    assertEquals(MERGED, run.commitSha);
    // The handle a cancellation and a retry address the work by. The sha above cannot be it: the
    // next re-fold replaces it.
    assertEquals(REQUEST_ID, run.releaseRequestId);
    assertEquals(CiRunStatus.SUCCESS, run.status);

    // The verdict returns keyed on the merged sha it received.
    assertEquals(1, announcer.announced().size());
    assertEquals(MERGED, announcer.announced().get(0).commitSha());
    assertEquals("release/" + REQUEST_ID, announcer.announced().get(0).branch());
    assertTrue(announcer.announced().get(0).gating(), "a green QA run is a gating verdict");
  }

  @Test
  public void anEventOfAnotherKindRecordsNoReleaseRequest() throws Exception {
    // The column is written only for the event that names one. A `releaseRequestId` on any other
    // payload is some other context's word, and a provenance column that reads any field of any
    // payload eventually records something nobody meant.
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            ".config/qits/ci-event-other.yml",
            """
            event: SCMPublishCommit
            steps:
              - image: alpine:3
                script: "true"
            """));

    deliver(
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(),
            "SCMPublishCommit",
            Instant.parse("2026-09-03T09:07:06Z"),
            "{\"releaseRequestId\":\"" + REQUEST_ID + "\"}"));

    assertNull(runService.runsFor(repoId).get(0).releaseRequestId);
  }

  // --- the gating / non-gating split inside ONE file ---------------------------------------------

  @Test
  public void aFailureInTheNonGatingHalfIsRedAndIsNotAGatingVerdict() throws Exception {
    seedQa();
    // The build passed and published; the userflow step is what went red.
    fakeRunner.script(1, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.FAILED, run.status, "a red step is a red run, whatever it is worth");
    assertFalse(run.gating, "the row records what the verdict was worth, not only what was declared");

    assertEquals(List.of(), announcer.announced());
    assertEquals(1, announcer.failed().size());
    FakeRunAnnouncer.AnnouncedFailure failure = announcer.failed().get(0);
    assertEquals("FAILED", failure.outcome());
    assertEquals(MERGED, failure.commitSha());
    assertFalse(
        failure.gating(),
        "a red userflow publish must not hold the commit — this is the whole of the one-file split");
  }

  @Test
  public void aFailureInTheGatingHalfIsAGatingVerdict() throws Exception {
    seedQa();
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(CiRunStatus.FAILED, run.status);
    assertTrue(run.gating);
    assertEquals(1, announcer.failed().size());
    assertTrue(announcer.failed().get(0).gating(), "the build and its tests are the gating half");
    assertEquals(REQUEST_ID, run.releaseRequestId);
  }

  @Test
  public void aNonGatingFileIsNotMadeGatingByItsSteps() throws Exception {
    // The AND, from the other side: the run's verdict is the file's flag ANDed with the failing
    // step's, so a `gating: false` file stays non-gating however its steps are declared.
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            QA_PATH,
            """
            event: ReleaseRequestChanged
            gating: false
            checkout:
              branch: backingBranch
              sha: mergedSha
            steps:
              - image: alpine:3
                script: "true"
            """));
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    deliver(arrival(UUID.randomUUID().toString(), payload(REQUEST_ID, MERGED)));

    assertFalse(runService.runsFor(repoId).get(0).gating);
    assertFalse(announcer.failed().get(0).gating());
  }

  @Test
  public void aNonGatingStepAlsoWorksInAFileThatDeclaresNoFileLevelFlag() throws Exception {
    // The AND, with the file's half left at its default: a gating file whose failing step said
    // `gating: false` announces a non-gating red. This is the "this half must not cost the image"
    // case the two-file split used to buy with a second file, and it is what lets a single QA
    // pipeline carry both halves.
    String repo = UUID.randomUUID().toString();
    String sha = UUID.randomUUID().toString().replace("-", "");
    fakeRunner.script(1, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));

    executePipeline(
        repo,
        "main",
        sha,
        """
        steps:
          - image: alpine:3
            script: build
          - image: alpine:3
            gating: false
            script: publish-docs
        """);
    forgetLoadedEntities();

    CiRun run = runService.runsFor(repo).get(0);
    assertEquals(CiRunStatus.FAILED, run.status);
    assertFalse(run.gating);
    assertFalse(announcer.failed().get(0).gating());
  }

  // --- fixture -----------------------------------------------------------------------------------

  private void seedQa() {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(QA_PATH, QA_TRIGGER));
  }

  private static String payload(String requestId, String mergedSha) {
    return "{\"projectId\":\"qits\",\"repoId\":\"r-1\",\"repoName\":\"qits-ci-service\","
        + "\"releaseRequestId\":\""
        + requestId
        + "\",\"backingBranch\":\"release/"
        + requestId
        + "\",\"mergedSha\":\""
        + mergedSha
        + "\"}";
  }

  private CiEventTriggerService.Arrival arrival(String eventId, String payload) {
    return new CiEventTriggerService.Arrival(
        eventId, "ReleaseRequestChanged", Instant.parse("2026-09-03T09:07:06Z"), payload);
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }
}
