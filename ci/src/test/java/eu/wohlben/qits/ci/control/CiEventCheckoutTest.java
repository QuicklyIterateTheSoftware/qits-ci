package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code checkout:} capability, end to end: a trigger that follows the event's own commit
 * records the run — and announces it — at the payload's branch and sha, refuses what the untrusted
 * payload cannot prove, and collapses a queued burst per branch the way the push path always has.
 */
@QuarkusTest
public class CiEventCheckoutTest extends CiTestSupport {

  private static final String CHECKOUT_PATH = ".config/qits/ci-event-build.yml";
  private static final String PLAIN_PATH = ".config/qits/ci-event-plain.yml";

  private static final String CHECKOUT_TRIGGER =
      """
      event: SCMPublishCommit
      checkout:
        branch: branch
        sha: sha
      steps:
        - image: alpine:3
          script: "true"
      """;

  private static final String PLAIN_TRIGGER =
      """
      event: SCMPublishCommit
      steps:
        - image: alpine:3
          script: "true"
      """;

  private static final String HEAD = "a".repeat(40);
  private static final String PUSHED = "b".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private final CountDownLatch release = new CountDownLatch(1);

  private String repoId;

  @BeforeEach
  void resetTriggerState() {
    repoId = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    announcer.reset();
  }

  @AfterEach
  void releaseTheWorker() {
    release.countDown();
  }

  @Test
  public void aCheckoutTriggerRecordsAndAnnouncesTheEventsOwnCommit() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER));
    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, push("feature/x", PUSHED)));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    CiRun run = recorded.get(0);
    // The row IS the truth downstream: the clone env, the restart snapshot and the announcement
    // all read these two columns, so payload-resolved values here fix the whole chain.
    assertEquals("feature/x", run.branch);
    assertEquals(PUSHED, run.commitSha);
    assertEquals(eventId, run.triggerEventId);
    assertEquals(CiRunStatus.SUCCESS, run.status);

    assertEquals(1, announcer.announced().size());
    assertEquals("feature/x", announcer.announced().get(0).branch());
    assertEquals(PUSHED, announcer.announced().get(0).commitSha());
  }

  @Test
  public void aPayloadMissingTheCheckoutFieldCostsThatFileItsRunAndNothingElse() throws Exception {
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER),
        new EventTriggerFile(PLAIN_PATH, PLAIN_TRIGGER));
    // No sha field at all — nothing truthful to record a row against.
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(arrival(UUID.randomUUID().toString(), "{\"branch\":\"feature/x\"}"));
    runService.awaitIdle();
    forgetLoadedEntities();

    // Containment is per FILE: the sibling trigger in the same repository still fired, and the
    // repository counts as read rather than skipped.
    assertEquals(1, evaluation.runIds().size());
    assertEquals(List.of(), evaluation.repositoriesSkipped());
    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    assertEquals(PLAIN_PATH, recorded.get(0).configPath);
    assertEquals("main", recorded.get(0).branch, "the plain sibling builds main's head");
  }

  @Test
  public void garbagePayloadValuesAreRefusedBeforeARowOrAUrlExists() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER));
    // The payload is attacker-shaped: both values reach a clone URL and an argv, so the
    // CiIdentifiers gate fires inside the per-file loop — refused, no run, no throw, and the
    // repository is not marked skipped.
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(
            arrival(
                UUID.randomUUID().toString(),
                "{\"branch\":\"-oProxyCommand=x\",\"sha\":\"$(x)\"}"));
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds());
    assertEquals(List.of(), evaluation.repositoriesSkipped());
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  @Test
  public void suppressCiIsAWhenConditionAndMatchesTheBooleanLiteral() throws Exception {
    // The engine gains no flag knowledge: suppression is declared in when:, and the matcher
    // compares the JSON literal — `exact: "false"` matches the boolean false. This is the shape
    // both qits-githost trigger files carry.
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(
            CHECKOUT_PATH,
            """
            event: SCMPublishCommit
            when:
              - suppressCi: { exact: "false" }
            checkout:
              branch: branch
              sha: sha
            steps:
              - image: alpine:3
                script: "true"
            """));
    deliver(
        arrival(
            UUID.randomUUID().toString(),
            "{\"branch\":\"main\",\"sha\":\"" + PUSHED + "\",\"suppressCi\":true}"));
    assertEquals(List.of(), runService.runsFor(repoId), "a -o qits.no-ci push stays dark");

    deliver(
        arrival(
            UUID.randomUUID().toString(),
            "{\"branch\":\"main\",\"sha\":\"" + PUSHED + "\",\"suppressCi\":false}"));
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void aPlatformTriggerDeclaringCheckoutRecordsNoRun() throws Exception {
    String platformId = "wrapper-" + UUID.randomUUID().toString().substring(0, 8);
    String targetId = "target-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      fakeCandidates.setRefs(
          CiRepoRef.of(platformId, "qits", "qits-qits"), CiRepoRef.of(targetId, "qits", "target"));
      fakeConfig.putTriggers(targetId, "main", HEAD);
      fakeConfig.putTriggers(
          platformId,
          "main",
          CiTriggerScope.PLATFORM,
          HEAD,
          new EventTriggerFile(".config/qits/ci-platform-event-build.yml", CHECKOUT_TRIGGER));
      engine.platformPipelinesRepository("qits-qits");

      deliver(
          arrival(
              UUID.randomUUID().toString(),
              "{\"repository\":\"target\",\"branch\":\"main\",\"sha\":\"" + PUSHED + "\"}"));

      assertEquals(List.of(), runService.runsFor(targetId));
      assertEquals(List.of(), runService.runsFor(platformId));
    } finally {
      engine.platformPipelinesRepository("");
    }
  }

  @Test
  public void aBurstToOneBranchCollapsesToTheNewestTipWhileOtherBranchesStand() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, CHECKOUT_TRIGGER));
    occupyTheWorker();

    String first = "c".repeat(40);
    String second = "d".repeat(40);
    String elsewhere = "e".repeat(40);
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/x", first)));
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/x", second)));
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/y", elsewhere)));
    release.countDown();
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(3, recorded.size());
    CiRun older = bySha(recorded, first);
    CiRun newest = bySha(recorded, second);
    CiRun otherBranch = bySha(recorded, elsewhere);
    assertEquals(
        CiRunStatus.CANCELLED,
        older.status,
        "the burst's older tip loses its queue slot — cancelled, because losing a slot is not a"
            + " verdict about the commit and a red row here is a false alarm");
    assertEquals(CiRunService.DEDUPED, older.cancellationReason, "and the reason says which");
    assertEquals(newest.id, older.supersededByRunId);
    assertNotEquals(CiRunStatus.FAILED, newest.status);
    assertNotEquals(CiRunStatus.CANCELLED, newest.status);
    assertNotEquals(CiRunStatus.FAILED, otherBranch.status, "another branch spends no slot here");
    // And the status change is a read surface only: a superseded row publishes no verdict, before
    // or after it, so qits-projects' build gate sees exactly what it always saw.
    assertTrue(
        announcer.failed().stream().noneMatch(failure -> failure.runId().equals(older.id)),
        "a superseded run announces no BuildFailed — it is bookkeeping about the queue");
    assertTrue(
        announcer.announced().stream().noneMatch(succeeded -> succeeded.runId().equals(older.id)),
        "and no BuildSuccessful either");
  }

  @Test
  public void nonCheckoutEventRunsAreNeverBranchCollapsed() throws Exception {
    // Without checkout: every event run's branch is "main" by convention, so a branch-keyed
    // collapse would dedupe runs of DISTINCT events — which the (trigger_event_id, …) contract
    // forbids. The gate is the trigger's checkout, and this is the case that pins it.
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(PLAIN_PATH, PLAIN_TRIGGER));
    occupyTheWorker();

    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/x", "c".repeat(40))));
    engine.evaluate(arrival(UUID.randomUUID().toString(), push("feature/y", "d".repeat(40))));
    release.countDown();
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size());
    assertTrue(
        recorded.stream().noneMatch(run -> run.status == CiRunStatus.FAILED),
        "two distinct events are two runs, whatever branch convention they share: " + recorded);
  }

  // --- fixture ---------------------------------------------------------------------------------

  private static String push(String branch, String sha) {
    return "{\"branch\":\"" + branch + "\",\"sha\":\"" + sha + "\",\"suppressCi\":false}";
  }

  private CiEventTriggerService.Arrival arrival(String eventId, String payload) {
    return new CiEventTriggerService.Arrival(
        eventId, "SCMPublishCommit", Instant.parse("2026-08-27T12:00:00Z"), payload);
  }

  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  private static CiRun bySha(List<CiRun> runs, String sha) {
    return runs.stream()
        .filter(run -> sha.equals(run.commitSha))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no run at " + sha + " in " + runs));
  }

  /**
   * Parks an unrelated push inside its first step and returns once the worker is really in it —
   * {@code CiTagSupersedeTest}'s staging, so everything accepted afterwards is genuinely queued.
   */
  private void occupyTheWorker() throws Exception {
    String blocker = "blocker-" + UUID.randomUUID().toString().substring(0, 8);
    String sha = "f".repeat(40);
    fakeConfig.put(
        blocker,
        sha,
        ConfigLookup.found("steps:\n  - image: alpine:3\n    script: \"true\"\n"));
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
    runService.onPostReceive(
        CiRepoRef.of(blocker), "main", "0".repeat(40), sha, UUID.randomUUID().toString());
    inStepZero.get(20, TimeUnit.SECONDS);
  }
}
