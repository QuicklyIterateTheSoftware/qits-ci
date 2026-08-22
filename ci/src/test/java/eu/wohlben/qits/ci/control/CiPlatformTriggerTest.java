package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Platform pipelines: one repository's {@code .config/qits/ci-platform-event-*.yml} evaluated for
 * every arriving event, with the run recorded against the repository the <b>payload</b> names.
 *
 * <p>Everything below the bus is real here too — the same parser, the same selection grammar, the
 * same run service and the same unique constraint — so what these cases are about is only the two
 * things a platform trigger does differently: which file it is read from, and which repository the
 * run it records is about.
 */
@QuarkusTest
public class CiPlatformTriggerTest extends CiTestSupport {

  private static final String PLATFORM_PATH = ".config/qits/ci-platform-event-maintenance-bump.yml";

  private static final String LOCAL_PATH = ".config/qits/ci-event-bump.yml";

  private static final String TRIGGER =
      """
      event: MaintenanceBump
      steps:
        - image: qits/build-images/maven-base:latest
          script: echo bump
      """;

  private static final String PLATFORM_HEAD = "a".repeat(40);

  private static final String TARGET_HEAD = "b".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private String platformId;
  private String targetId;

  @BeforeEach
  void armThePlatformRepository() {
    platformId = "wrapper-" + UUID.randomUUID().toString().substring(0, 8);
    targetId = "target-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(
        CiRepoRef.of(platformId, "qits", "qits-qits"),
        CiRepoRef.of(targetId, "qits", "qits-target"));
    // The head the target repository's main is on for this evaluation — what a platform run is
    // recorded at, and what its step containers check out.
    fakeConfig.putTriggers(targetId, "main", TARGET_HEAD);
    engine.platformPipelinesRepository("qits-qits");
    announcer.reset();
  }

  @AfterEach
  void disarm() {
    engine.platformPipelinesRepository("");
  }

  private CiEventTriggerService.Arrival arrival(String payload) {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(),
        "MaintenanceBump",
        Instant.parse("2026-08-21T09:00:00Z"),
        payload);
  }

  private void seedPlatformTrigger(String content) {
    fakeConfig.putTriggers(
        platformId,
        "main",
        CiTriggerScope.PLATFORM,
        PLATFORM_HEAD,
        new EventTriggerFile(PLATFORM_PATH, content));
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  private static String payloadNaming(String repository) {
    return "{\"repository\":\"" + repository + "\",\"group\":\"dependencies\"}";
  }

  // --- the happy path ---

  @Test
  public void aPlatformTriggerRecordsARunAgainstThePayloadsRepository() throws Exception {
    seedPlatformTrigger(TRIGGER);
    CiEventTriggerService.Arrival arrival = arrival(payloadNaming("qits-target"));

    deliver(arrival);

    List<CiRun> recorded = runService.runsFor(targetId);
    assertEquals(1, recorded.size(), "the run belongs to the repository the payload named");
    CiRun run = recorded.get(0);
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(CiTriggerType.EVENT, run.triggerType);
    assertEquals(arrival.eventId(), run.triggerEventId);
    assertEquals("MaintenanceBump", run.triggerEventName);
    // The row records WHICH file declared the run, and the platform prefix is what tells the two
    // kinds apart wherever a run is read back.
    assertEquals(PLATFORM_PATH, run.configPath);
    // The target's own head, never the platform repository's — the steps clone the target.
    assertEquals("main", run.branch);
    assertEquals(TARGET_HEAD, run.commitSha);
    assertEquals("qits-target", run.repoName, "the public coordinate travels to the clone url");
    assertEquals(List.of(), runService.runsFor(platformId), "the file's own repository is not built");
  }

  @Test
  public void thePlatformRunsStepsSeeTheWholeEventLikeAnyOtherEventRun() throws Exception {
    seedPlatformTrigger(TRIGGER);
    CiEventTriggerService.Arrival arrival = arrival(payloadNaming("qits-target"));

    deliver(arrival);

    var env = fakeRunner.executed().get(0).env();
    assertEquals(arrival.eventId(), env.get("QITS_EVENT_ID"));
    assertEquals("MaintenanceBump", env.get("QITS_EVENT_NAME"));
    assertEquals(arrival.payload(), env.get("QITS_EVENT_PAYLOAD"));
  }

  @Test
  public void theGreenRunAnnouncesTheTARGETsNamePairAndNotTheWrappers() throws Exception {
    // The one thing this feature could get wrong that no other trigger can: the file comes from the
    // wrapper and the build is somebody else's, so an announcement carrying the file's repository
    // would have the deployer looking for qits/qits-qits:<sha>. The pair rides off the run's own
    // row, and the row is the target's.
    seedPlatformTrigger(TRIGGER);

    deliver(arrival(payloadNaming("qits-target")));

    assertEquals(1, announcer.announced().size());
    FakeRunAnnouncer.Announced announced = announcer.announced().get(0);
    assertEquals(targetId, announced.repoId());
    assertEquals("qits", announced.projectId());
    assertEquals("qits-target", announced.repoName());
    assertEquals(TARGET_HEAD, announced.commitSha());
  }

  @Test
  public void aWhenOnThePlatformFileSelectsExactlyAsARepositorysOwnWould() throws Exception {
    seedPlatformTrigger(
        """
        event: MaintenanceBump
        when:
          - group: { exact: angular }
        steps: []
        """);

    deliver(arrival(payloadNaming("qits-target")));

    assertEquals(
        List.of(), runService.runsFor(targetId), "the payload's group is 'dependencies', not 'angular'");
  }

  // --- the repository the payload names ---

  @Test
  public void anEventNamingNoRepositoryRecordsNothing() throws Exception {
    seedPlatformTrigger(TRIGGER);

    deliver(arrival("{\"group\":\"dependencies\"}"));

    assertEquals(List.of(), runService.runsFor(targetId));
    assertEquals(List.of(), runService.runsFor(platformId));
  }

  @Test
  public void anEventNamingARepositoryThePlatformDoesNotHoldRecordsNothing() throws Exception {
    seedPlatformTrigger(TRIGGER);

    deliver(arrival(payloadNaming("no-such-repository")));

    assertEquals(List.of(), runService.runsFor(targetId));
    assertEquals(List.of(), runService.runsFor(platformId));
  }

  // --- the two files are two pipelines ---

  @Test
  public void aRepositoryWithBothKindsOfTriggerGetsTwoRuns() throws Exception {
    seedPlatformTrigger(TRIGGER);
    fakeConfig.putTriggers(
        targetId, "main", TARGET_HEAD, new EventTriggerFile(LOCAL_PATH, TRIGGER));

    deliver(arrival(payloadNaming("qits-target")));

    List<String> paths = runService.runsFor(targetId).stream().map(run -> run.configPath).sorted().toList();
    assertEquals(
        List.of(LOCAL_PATH, PLATFORM_PATH),
        paths,
        "two files are two declared pipelines, and the dedupe is per file");
  }

  @Test
  public void thePlatformRepositorysOwnLocalTriggersStillFire() throws Exception {
    seedPlatformTrigger(TRIGGER);
    fakeConfig.putTriggers(
        platformId, "main", PLATFORM_HEAD, new EventTriggerFile(LOCAL_PATH, TRIGGER));

    deliver(arrival(payloadNaming("qits-target")));

    assertEquals(
        List.of(LOCAL_PATH),
        runService.runsFor(platformId).stream().map(run -> run.configPath).toList(),
        "the wrapper's own ci-event-*.yml is a repository trigger like anybody else's");
  }

  // --- off means no read ---

  @Test
  public void aBlankPlatformRepositoryReadsNothingAtAll() throws Exception {
    engine.platformPipelinesRepository("");
    seedPlatformTrigger(TRIGGER);

    deliver(arrival(payloadNaming("qits-target")));

    assertFalse(
        fakeConfig.triggerReads().stream().anyMatch(read -> read.endsWith("#PLATFORM")),
        "off must cost no listing: " + fakeConfig.triggerReads());
    assertEquals(List.of(), runService.runsFor(targetId));
  }

  @Test
  public void anArmedPlatformRepositoryIsReadOnceForItsOwnScope() throws Exception {
    seedPlatformTrigger(TRIGGER);

    deliver(arrival(payloadNaming("qits-target")));

    assertEquals(
        1,
        fakeConfig.triggerReads().stream().filter(read -> read.endsWith("#PLATFORM")).count(),
        "one platform listing per arriving event, whatever the catalogue's size");
    assertTrue(fakeConfig.triggerReads().contains(platformId + "@main#PLATFORM"));
  }
}
