package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * <b>A push of many tags builds the newest one, once.</b>
 *
 * <p>The git host announces one {@code SCMPublishTag} per tag ref of a push and is right to: the
 * event is a fact about the repository, and qits-projects' backup consumer needs every one of them.
 * So a release push writing five tags puts five matching events on the bus, and a repository whose
 * {@code ci-event-*.yml} declares {@code event: SCMPublishTag} would otherwise get five runs of one
 * pipeline — four of them building a version nobody asked for. The collapse therefore belongs to the
 * consumer, and it is {@code CiRunService.supersedeByVersion}.
 *
 * <p>Every claim is staged against a <b>genuinely occupied worker</b>, the way {@code
 * CiQueuedRunTest} stages {@code QUEUED}: the worker is single-threaded in this suite, so a run
 * parked inside its first step makes everything accepted after it really queue, at an instant the
 * test controls rather than one it hopes to catch.
 *
 * <p>It also holds the half that needed no code. {@code CiEventTriggerListener} subscribes to {@code
 * "*"} and the engine matches a trigger file's {@code event:} against the arriving name as a string,
 * so a repository could already select the tag event by naming it. Every case below arrives through
 * {@code CiEventTriggerService.evaluate} rather than through a hand-built run request, which is what
 * keeps that a measurement.
 */
@QuarkusTest
public class CiTagSupersedeTest extends CiTestSupport {

  private static final String TAG_EVENT = "SCMPublishTag";
  private static final String RELEASE_TRIGGER = ".config/qits/ci-event-release.yml";
  private static final String DOCS_TRIGGER = ".config/qits/ci-event-docs.yml";
  private static final String HEAD = "e".repeat(40);

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo one
      """;

  @Inject CiEventTriggerService engine;
  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;

  /** Opened by the test, awaited on the worker inside the blocking run's first step. */
  private final CountDownLatch release = new CountDownLatch(1);

  private String repoId;

  /**
   * Runs after {@code CiTestSupport}'s reset, which empties the candidate list — so this repository
   * is the only one any event below is evaluated against, and no case can fire another's trigger.
   */
  @BeforeEach
  void mintRepository() {
    repoId = "tagged-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    announcer.reset();
  }

  @AfterEach
  void releaseTheWorker() throws Exception {
    release.countDown();
    service.awaitIdle();
  }

  /** A trigger file for one event name, selecting this repository's events and nothing else. */
  private String trigger(String eventName) {
    return """
        event: %s
        when:
          - repoId: { exact: %s }
        steps:
          - image: alpine:3
            script: echo release
        """
        .formatted(eventName, repoId);
  }

  /** Declares what this repository's {@code main} head carries — one tag trigger unless told else. */
  private void declares(EventTriggerFile... files) {
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        files.length > 0
            ? files
            : new EventTriggerFile[] {new EventTriggerFile(RELEASE_TRIGGER, trigger(TAG_EVENT))});
  }

  /**
   * Parks an unrelated push inside its first step and returns once the worker is really in it.
   * Everything accepted after this call is genuinely queued.
   */
  private void occupyTheWorker() throws Exception {
    String blocker = "blocker-" + UUID.randomUUID().toString().substring(0, 8);
    String sha = "b".repeat(40);
    fakeConfig.put(blocker, sha, ConfigLookup.found(CONFIG_ONE_STEP));
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
    service.onPostReceive(
        CiRepoRef.of(blocker), "main", "0".repeat(40), sha, UUID.randomUUID().toString());
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  /** One tag announcement, evaluated exactly as the trigger worker evaluates an arriving frame. */
  private void announceTag(String tagName) {
    evaluate(
        TAG_EVENT,
        "{\"repoId\":\"%s\",\"sha\":\"%s\",\"tagName\":\"%s\",\"annotated\":false}"
            .formatted(repoId, HEAD, tagName));
  }

  private void evaluate(String eventName, String payload) {
    engine.evaluate(
        new CiEventTriggerService.Arrival(
            UUID.randomUUID().toString(), eventName, Instant.now(), payload));
  }

  private List<CiRun> recorded() {
    forgetLoadedEntities();
    return service.runsFor(repoId);
  }

  /** The run recorded for one announced tag — of one trigger file, when a case seeds two. */
  private CiRun runFor(String tagName) {
    return recorded().stream()
        .filter(run -> RELEASE_TRIGGER.equals(run.configPath))
        .filter(run -> run.triggerEventPayload.contains("\"tagName\":\"" + tagName + "\""))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no run recorded for tag " + tagName));
  }

  /** Of everything recorded for this repository, what is still going to happen. */
  private List<CiRun> stillQueued() {
    return recorded().stream().filter(run -> run.status == CiRunStatus.QUEUED).toList();
  }

  private void assertSuperseded(CiRun loser, CiRun winner) {
    // CANCELLED, not FAILED: a superseded run answered no question, so it must not read as a build
    // that broke. The reason is what says which cancellation it was.
    assertEquals(CiRunStatus.CANCELLED, loser.status);
    assertEquals(CiRunService.DEDUPED, loser.cancellationReason);
    assertNotNull(loser.finishedAt, "a superseded run is over");
    // And it published no verdict either way. The row is written at accept time and reaches neither
    // announcer, so the corrected status is a read surface and nothing downstream reads a new
    // failure class off it — qits-projects' gate matches on (repoId, commitSha) and sees nothing.
    assertTrue(
        announcer.failed().stream().noneMatch(failure -> failure.runId().equals(loser.id)),
        "a deduped run announces no BuildFailed");
    assertTrue(
        announcer.announced().stream().noneMatch(green -> green.runId().equals(loser.id)),
        "and no BuildSuccessful either");
    // Each row names what beat it AT THE TIME, so tags arriving out of order leave a chain rather
    // than a star — every hop is a true statement, and the chain ends at the run that stands.
    String at = loser.supersededByRunId;
    for (int hop = 0; at != null && !at.equals(winner.id) && hop < 10; hop++) {
      at = service.requireRun(at).supersededByRunId;
    }
    assertEquals(winner.id, at, "the supersede chain of " + loser.id + " ends at the queued run");
  }

  // --- the whole point ---

  @Test
  public void aPushOfManyTagsLeavesOneQueuedRunAndItIsTheNewestTag() throws Exception {
    declares();
    occupyTheWorker();

    // One push, five tag refs, five events, in the order a fan-out happens to emit them — which is
    // no order at all. Note 184518 against 98: plain string order gets that pair backwards.
    announceTag("2026.810.98");
    announceTag("2026.811.1");
    announceTag("2026.79.240000");
    announceTag("2026.810.184518");
    announceTag("2026.811.10");

    // Every tag is on the record — the events happened and the rows say so — and exactly one of
    // them is still going to build.
    assertEquals(5, recorded().size(), "one row per announced tag");
    CiRun winner = runFor("2026.811.10");
    assertEquals(List.of(winner.id), stillQueued().stream().map(run -> run.id).toList());
    for (String beaten :
        List.of("2026.810.98", "2026.811.1", "2026.79.240000", "2026.810.184518")) {
      assertSuperseded(runFor(beaten), winner);
    }

    release.countDown();
    service.awaitIdle();
    forgetLoadedEntities();

    // And the worker really built that one: the superseded rows were dropped at the claim rather
    // than run and overwritten.
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(winner.id).status);
    assertEquals(
        1,
        fakeRunner.executed().stream().filter(spec -> spec.repo().repoId().equals(repoId)).count(),
        "a five-tag push starts one pipeline");
  }

  @Test
  public void theNewestTagArrivingFirstSupersedesTheOnesBehindIt() throws Exception {
    // The other order, and the one that has to mark the run being ACCEPTED rather than the ones
    // already queued. Same net effect, which is the whole claim.
    declares();
    occupyTheWorker();

    announceTag("2026.811.10");
    announceTag("2026.810.184518");
    announceTag("2026.810.98");

    CiRun winner = runFor("2026.811.10");
    assertEquals(List.of(winner.id), stillQueued().stream().map(run -> run.id).toList());
    assertSuperseded(runFor("2026.810.184518"), winner);
    assertSuperseded(runFor("2026.810.98"), winner);
  }

  @Test
  public void aSingleTagIsLeftCompletelyAlone() throws Exception {
    // The ordinary case, and the one a supersede must not touch: nothing to compare against is
    // nothing to collapse.
    declares();
    occupyTheWorker();

    announceTag("2026.811.10");

    CiRun only = runFor("2026.811.10");
    assertEquals(CiRunStatus.QUEUED, only.status);
    assertNull(only.cancellationReason);
    assertNull(only.supersededByRunId);
    assertNull(only.finishedAt);
  }

  @Test
  public void aTagThatMovedSupersedesItsOwnEarlierRun() throws Exception {
    // Same name announced twice: the tag was re-pushed at another commit, so the later announcement
    // is the current one — the rule a second push to one branch already gets.
    declares();
    occupyTheWorker();

    announceTag("2026.811.10");
    String first = runFor("2026.811.10").id;
    announceTag("2026.811.10");

    List<CiRun> queued = stillQueued();
    assertEquals(1, queued.size(), "one of the two stands");
    assertNotEquals(first, queued.get(0).id, "and it is the later announcement");
    forgetLoadedEntities();
    assertSuperseded(service.requireRun(first), queued.get(0));
  }

  // --- what it must not reach ---

  @Test
  public void eachTriggerFileKeepsItsOwnRun() throws Exception {
    // Two files are two declared pipelines, by design. The supersede is per trigger file for the
    // same reason the push supersede is per branch.
    declares(
        new EventTriggerFile(RELEASE_TRIGGER, trigger(TAG_EVENT)),
        new EventTriggerFile(DOCS_TRIGGER, trigger(TAG_EVENT)));
    occupyTheWorker();

    announceTag("2026.810.98");
    announceTag("2026.811.10");

    assertEquals(4, recorded().size(), "two files, two tags");
    assertEquals(2, stillQueued().size(), "one queued run per trigger file");
    assertEquals(
        List.of(DOCS_TRIGGER, RELEASE_TRIGGER),
        stillQueued().stream().map(run -> run.configPath).sorted().toList());
  }

  @Test
  public void eventsOfAnotherNameAreNeverCollapsed() throws Exception {
    // No other event on this bus carries a field that orders, so nothing but a tag may enter the
    // supersede — two BuildSuccessful events are two independent reasons to build.
    declares(new EventTriggerFile(RELEASE_TRIGGER, trigger("BuildSuccessful")));
    occupyTheWorker();

    evaluate("BuildSuccessful", "{\"repoId\":\"%s\"}".formatted(repoId));
    evaluate("BuildSuccessful", "{\"repoId\":\"%s\"}".formatted(repoId));

    assertEquals(2, stillQueued().size(), "both stand");
  }

  @Test
  public void aPayloadWithNoTagNameSupersedesNothing() throws Exception {
    // A failure to compare is not a lower version. Superseding on it would let one unreadable
    // payload cancel a build that was going to be right.
    declares();
    occupyTheWorker();

    evaluate(TAG_EVENT, "{\"repoId\":\"%s\"}".formatted(repoId));
    announceTag("2026.810.98");
    evaluate(TAG_EVENT, "{\"repoId\":\"%s\"}".formatted(repoId));

    assertEquals(3, recorded().size());
    assertEquals(3, stillQueued().size(), "nothing comparable, nothing collapsed");
  }
}
