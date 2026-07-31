package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The trigger engine, from an arriving event to a recorded run: which repositories are asked, which
 * files match, what the run row says about why it exists, and what its step containers are handed.
 *
 * <p>Everything below the bus is real — the parser, the evaluator, the run service, the worker, the
 * unique constraint — and everything above it is not: the frame arrives as {@link
 * CiEventTriggerService.Arrival}, which is the record the raw listener in {@code service/…/bus}
 * builds. That split is the same one {@code RunAnnounceSeamTest} makes on the publishing side and it
 * is what keeps the {@code ci} module free of the bus. The bus half — a real frame down the raw path,
 * and the {@code parentId} on the resulting PUT — is {@code CiEventTriggerCausationTest} in the
 * service module.
 */
@QuarkusTest
public class CiEventTriggerServiceTest extends CiTestSupport {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-upstream.yml";

  private static final String TRIGGER =
      """
      event: BuildSuccessful
      when:
        - repoId: { exact: qits-spa-ui-components }
          branch: { exact: main }
      steps:
        - image: alpine:3
          script: echo bump
      """;

  private static final String PAYLOAD =
      "{\"branch\":\"main\",\"commitSha\":\"cafebabe\",\"repoId\":\"qits-spa-ui-components\"}";

  private static final String HEAD = "a".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;
  @Inject FakeRunAnnouncer announcer;

  private String repoId;

  @BeforeEach
  void resetTriggerState() {
    repoId = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    announcer.reset();
  }

  private CiEventTriggerService.Arrival arrival(String eventId, String name, String payload) {
    return new CiEventTriggerService.Arrival(
        eventId, name, Instant.parse("2026-07-31T12:46:03Z"), payload);
  }

  private void seedTrigger(String path, String content) {
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(path, content));
  }

  /** Drives evaluation and the run it enqueues to completion, without either worker's timing. */
  private void deliver(CiEventTriggerService.Arrival arrival) throws Exception {
    engine.evaluate(arrival);
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  // --- the happy path, and the whole of the provenance ---

  @Test
  public void aMatchingEventRecordsARunWithItsWholeProvenance() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, "BuildSuccessful", PAYLOAD));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "one matching trigger is one run");
    CiRun run = recorded.get(0);
    assertEquals(CiRunStatus.SUCCESS, run.status);
    assertEquals(CiTriggerType.EVENT, run.triggerType);
    assertEquals(eventId, run.triggerEventId);
    assertEquals("BuildSuccessful", run.triggerEventName);
    assertEquals(TRIGGER_PATH, run.configPath);
    // An event names no commit: the run is about the head of main as of the moment it arrived.
    assertEquals("main", run.branch);
    assertEquals(HEAD, run.commitSha);
  }

  @Test
  public void aPushKeepsRecordingAPostReceiveRunWithNoEvent() {
    // The other trigger type, asserted here so the pair reads in one place — and because every one
    // of these rows has a NULL trigger_event_id, which the unique constraint must let past.
    String sha = "b".repeat(40);
    fakeConfig.put(
        repoId, sha, CiConfigSource.ConfigLookup.found("steps:\n  - image: alpine:3\n    script: x\n"));
    runService.execute(repoId, "main", sha);

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(CiTriggerType.POST_RECEIVE, run.triggerType);
    assertNull(run.triggerEventId);
    assertNull(run.triggerEventName);
    assertEquals(CiConfigParser.CONFIG_PATH, run.configPath);
  }

  // --- what the steps see ---

  @Test
  public void theStepContainerIsHandedTheWholeEventAsEnvironment() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, "BuildSuccessful", PAYLOAD));

    Map<String, String> env = fakeRunner.executed().get(0).env();
    assertEquals(eventId, env.get("QITS_EVENT_ID"));
    assertEquals("BuildSuccessful", env.get("QITS_EVENT_NAME"));
    assertEquals("2026-07-31T12:46:03Z", env.get("QITS_EVENT_OCCURRED_AT"));
    // Verbatim, as the canonical JSON qits-events stored — no per-field flattening. A step that
    // wants one field uses jq, which the step images carry.
    assertEquals(PAYLOAD, env.get("QITS_EVENT_PAYLOAD"));
    assertEquals(4, env.size(), "the four are the whole of it");
  }

  @Test
  public void aPushedRunGetsNoEventEnvironmentAtAll() {
    String sha = "c".repeat(40);
    fakeConfig.put(
        repoId, sha, CiConfigSource.ConfigLookup.found("steps:\n  - image: alpine:3\n    script: x\n"));
    runService.execute(repoId, "main", sha);
    assertEquals(Map.of(), fakeRunner.executed().get(0).env());
  }

  // --- the causation stamp ---

  @Test
  public void theAnnouncerIsHandedTheTriggeringEventId() throws Exception {
    // The whole of Decision 7 on this side: the id crossed a thread and a database row to get here,
    // and it is what the run's own BuildSuccessful is published under as parentId.
    seedTrigger(TRIGGER_PATH, TRIGGER);
    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, "BuildSuccessful", PAYLOAD));

    assertEquals(1, announcer.announced().size());
    assertEquals(eventId, announcer.announced().get(0).triggerEventId());
  }

  @Test
  public void aPushedRunAnnouncesANullCauseAndPublishesARoot() {
    String sha = "d".repeat(40);
    fakeConfig.put(
        repoId, sha, CiConfigSource.ConfigLookup.found("steps:\n  - image: alpine:3\n    script: x\n"));
    runService.execute(repoId, "main", sha);

    assertEquals(1, announcer.announced().size());
    assertNull(
        announcer.announced().get(0).triggerEventId(), "a push is not caused by an event");
  }

  // --- what does not fire ---

  @Test
  public void anEventOfAnotherNameFiresNothing() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    deliver(arrival(UUID.randomUUID().toString(), "SomethingElse", PAYLOAD));
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  @Test
  public void aSelectionThatDoesNotMatchFiresNothing() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    deliver(
        arrival(
            UUID.randomUUID().toString(),
            "BuildSuccessful",
            "{\"branch\":\"main\",\"repoId\":\"some-other-repo\"}"));
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  @Test
  public void aRepositoryDeclaringNoTriggerIsAskedAndFiresNothing() throws Exception {
    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));
    assertTrue(fakeConfig.triggerReads().contains(repoId + "@main"), "the candidate was asked");
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  @Test
  public void anUnconditionalTriggerFiresOnEveryEventOfItsName() throws Exception {
    // The documented meaning of an absent `when`, asserted where a trigger author would look.
    seedTrigger(TRIGGER_PATH, "event: BuildSuccessful\nsteps: []\n");
    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", "{\"anything\":\"at all\"}"));
    assertEquals(1, runService.runsFor(repoId).size());
  }

  // --- isolation ---

  @Test
  public void oneUnparseableTriggerFileDoesNotDisableItsSiblings() throws Exception {
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(".config/qits/ci-event-broken.yml", "event: BuildSuccessful\nwehn: []\n"),
        new EventTriggerFile(TRIGGER_PATH, TRIGGER));

    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, "BuildSuccessful", PAYLOAD));

    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "the sibling still fired");
    assertEquals(TRIGGER_PATH, recorded.get(0).configPath);
  }

  @Test
  public void anUnreachableRepositoryDoesNotStopTheOthersBeingEvaluated() throws Exception {
    String broken = "gone-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(broken, repoId);
    fakeConfig.putTriggersUnreachable(broken, "main");
    seedTrigger(TRIGGER_PATH, TRIGGER);

    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));
    assertEquals(1, runService.runsFor(repoId).size());
    assertEquals(List.of(), runService.runsFor(broken));
  }

  @Test
  public void twoTriggerFilesMatchingOneEventAreTwoRunsByDesign() throws Exception {
    // They are two declared pipelines; the constraint is per (event, repo, FILE).
    fakeConfig.putTriggers(
        repoId,
        "main",
        HEAD,
        new EventTriggerFile(".config/qits/ci-event-a.yml", TRIGGER),
        new EventTriggerFile(".config/qits/ci-event-b.yml", TRIGGER));

    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));
    assertEquals(
        List.of(".config/qits/ci-event-a.yml", ".config/qits/ci-event-b.yml"),
        runService.runsFor(repoId).stream().map(r -> r.configPath).sorted().toList());
  }

  // --- the engine must never throw into the socket path ---

  @Test
  public void onEventNeverThrowsWhateverArrives() {
    // The caller is a socket callback delivering to every other consumer too, and a throw out of it
    // is swallowed at ERROR by the dispatcher — so it must not happen rather than be relied upon.
    engine.onEvent(null);
    engine.onEvent(arrival(null, "BuildSuccessful", PAYLOAD));
    engine.onEvent(arrival(UUID.randomUUID().toString(), null, PAYLOAD));
    engine.onEvent(arrival(UUID.randomUUID().toString(), "BuildSuccessful", null));
    engine.onEvent(arrival(UUID.randomUUID().toString(), "BuildSuccessful", "not json {"));
  }

  @Test
  public void onEventHandsOffRatherThanEvaluatingOnTheCallersThread() throws Exception {
    // Evaluation does git IO per candidate repository; doing it on the dispatch thread would stall
    // the whole subscription behind a git host. Asserted as "the caller returns before the work is
    // visible, and the work happens afterwards" rather than by timing anything.
    seedTrigger(TRIGGER_PATH, TRIGGER);
    fakeCandidates.blockUntilReleased();

    engine.onEvent(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));
    assertFalse(fakeCandidates.released(), "onEvent returned while evaluation was still parked");
    assertEquals(List.of(), runService.runsFor(repoId));

    fakeCandidates.release();
    engine.awaitIdle();
    runService.awaitIdle();
    forgetLoadedEntities();
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void noCandidatesIsNotAnError() throws Exception {
    fakeCandidates.set();
    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));
    assertNotNull(runService.runsFor(repoId));
  }
}
