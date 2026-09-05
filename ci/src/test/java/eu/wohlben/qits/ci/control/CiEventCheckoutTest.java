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

  /**
   * A release recipe's checkout, verbatim in shape: the anchor is the tag's own NAME and the commit
   * it points at, and the pair is declared {@code optional} because the event has not always carried
   * the second one. This is what {@code .config/qits/ci-event-release.yml} spells.
   */
  private static final String RELEASE_TRIGGER =
      """
      event: SCMRelease
      checkout:
        branch: version
        sha: commitSha
        optional: true
      steps:
        - image: alpine:3
          script: "true"
      """;

  private static final String HEAD = "a".repeat(40);
  private static final String PUSHED = "b".repeat(40);
  private static final String RELEASED = "c0ffee1".repeat(5) + "abcde";

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

  // --- the release anchor: a checkout whose ref is a TAG ---------------------------------------

  /**
   * <b>A release run is anchored at the tag, and the engine learns nothing about tags to do it.</b>
   *
   * <p>{@code checkout.branch} is a path to a REF NAME — the column is called branch because that is
   * what a run row has always called its ref — so pointing it at the release event's {@code version}
   * records the run at the tag and hands the daemon {@code clone --branch <tag>}, which git resolves
   * exactly as it resolves a head. Everything the row, the clone env and the announcement carry
   * follows from these two columns, which is why this is the assertion that matters: before it, a
   * release run said {@code main@<head>} and the released tree was named only inside a step script.
   */
  @Test
  public void aReleaseTriggerRecordsTheRunAtTheTagAndTheCommitItPointsAt() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    String eventId = UUID.randomUUID().toString();
    deliver(releaseEvent(eventId, "2026.905.60215", RELEASED));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size());
    CiRun run = recorded.get(0);
    assertEquals("2026.905.60215", run.branch, "a calver is a ref name and passes the ref gate");
    assertEquals(RELEASED, run.commitSha);
    assertEquals(CiRunStatus.SUCCESS, run.status);

    assertEquals(1, announcer.announced().size());
    assertEquals("2026.905.60215", announcer.announced().get(0).branch());
    assertEquals(RELEASED, announcer.announced().get(0).commitSha());
  }

  /**
   * <b>The compatibility arm, which is the whole reason {@code optional:} exists.</b>
   *
   * <p>{@code commitSha} is an additive field on {@code SCMRelease}: a release published before it
   * existed — a replay out of the durable log, an older publisher, a rolled-back one — carries no
   * such key. The default answer to an unresolvable checkout is to cost the file its run, which for
   * a release pipeline means a tag that exists and an image that silently never gets published. With
   * {@code optional: true} the run is recorded at {@code main}'s head instead, which is byte-for-byte
   * what the recipe did before it declared a checkout at all — and its step script's own
   * {@code git fetch refs/tags/$version} is what then supplies the released tree.
   */
  @Test
  public void aReleaseCarryingNoCommitShaStillRunsAtMainsHead() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    deliver(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", null));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "an older release event must not cost the pipeline its run");
    assertEquals("main", recorded.get(0).branch);
    assertEquals(HEAD, recorded.get(0).commitSha);
    assertEquals(CiRunStatus.SUCCESS, recorded.get(0).status);
  }

  /**
   * <b>A fallback run IS a checkout-less run, and the per-ref collapse is where that has teeth.</b>
   *
   * <p>Two distinct releases that both fall back are both recorded at {@code main}, so a collapse
   * keyed on the ref would dedupe one of them away — and a deduped release run is a version that was
   * tagged and never built. The engine hands such a run on with its checkout stripped rather than
   * merely logging the fallback, so this holds for every reader keyed on {@code checkout}, not only
   * for the one we remembered. It is {@link #nonCheckoutEventRunsAreNeverBranchCollapsed}'s claim,
   * asserted for the trigger that DECLARES a checkout and did not get to use it.
   */
  @Test
  public void twoFallbackReleasesAreTwoRunsRatherThanACollapsedOne() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    occupyTheWorker();

    engine.evaluate(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", null));
    engine.evaluate(releaseEvent(UUID.randomUUID().toString(), "2026.905.70000", null));
    release.countDown();
    runService.awaitIdle();
    forgetLoadedEntities();

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(2, recorded.size());
    assertTrue(
        recorded.stream().noneMatch(run -> run.status == CiRunStatus.FAILED),
        "a collapsed fallback is a released version whose image is never published: " + recorded);
  }

  /**
   * {@code optional:} is about ABSENCE and softens no guard. A payload that carries a hostile value
   * where the sha belongs is refused exactly as it is without the flag — the fallback would
   * otherwise be a way to make a garbage payload build something rather than nothing.
   */
  @Test
  public void anOptionalCheckoutStillRefusesAValueThatIsThereAndHostile() throws Exception {
    fakeConfig.putTriggers(
        repoId, "main", HEAD, new EventTriggerFile(CHECKOUT_PATH, RELEASE_TRIGGER));
    CiEventTriggerService.Evaluation evaluation =
        engine.evaluate(releaseEvent(UUID.randomUUID().toString(), "2026.905.60215", "$(x)"));
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(List.of(), evaluation.runIds());
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  // --- fixture ---------------------------------------------------------------------------------

  /** One release event, with or without the commit its tag points at. */
  private CiEventTriggerService.Arrival releaseEvent(String eventId, String version, String commitSha) {
    String payload =
        commitSha == null
            ? "{\"repository\":\"r\",\"version\":\"" + version + "\"}"
            : "{\"repository\":\"r\",\"version\":\""
                + version
                + "\",\"commitSha\":\""
                + commitSha
                + "\"}";
    return new CiEventTriggerService.Arrival(
        eventId, "SCMRelease", Instant.parse("2026-09-05T06:02:15Z"), payload);
  }

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
