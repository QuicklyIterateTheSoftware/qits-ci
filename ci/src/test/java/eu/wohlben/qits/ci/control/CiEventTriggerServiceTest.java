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
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.ci.entity.CiOwedEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
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

  /** The platform's one QA pipeline, in the shape a repository really commits it. */
  private static final String RELEASE_REQUEST_PATH = ".config/qits/ci-event-release-request.yml";

  private static final String RELEASE_REQUEST_TRIGGER =
      """
      event: ReleaseRequestChanged
      steps:
        - image: alpine:3
          script: echo qa
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
  public void aNamedCandidateIsReadNameAddressedAndItsRunRecordsThePair() throws Exception {
    // The candidate unit is (repoId, projectId, name), so the engine reads the trigger files at the
    // public address and the run it accepts carries the pair — which is what every URL that run
    // builds afterwards comes from.
    CiRepoRef named = CiRepoRef.of(repoId, "qits", "qits-blobstore");
    fakeCandidates.setRefs(named);
    seedTrigger(TRIGGER_PATH, TRIGGER);

    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));

    assertTrue(
        fakeConfig.addressed().contains(named),
        "the trigger read went out with the public coordinate: " + fakeConfig.addressed());
    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals("qits", run.projectId);
    assertEquals("qits-blobstore", run.repoName);
    assertEquals(repoId, run.repoId, "the storage id stays the key the row is found by");
  }

  @Test
  public void anUnnamedCandidateIsReadIdAddressedAndRecordsNoPair() throws Exception {
    // The compatibility arm: a candidate qits-ci knows only from its own run rows has no public
    // address, so it is read exactly as it always was and its run records no names.
    seedTrigger(TRIGGER_PATH, TRIGGER);

    deliver(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));

    assertTrue(fakeConfig.addressed().contains(CiRepoRef.of(repoId)));
    CiRun run = runService.runsFor(repoId).get(0);
    assertNull(run.projectId);
    assertNull(run.repoName);
  }

  @Test
  public void aTriggerFileNamingTheRepositoryMatchesAnEventCarryingAUuidAndThatName()
      throws Exception {
    // The estate's own files, unedited, against a post-cutover event: the payload's repoId is an
    // opaque storage UUID and the file names the repository, so the matcher reads repoName.
    seedTrigger(
        TRIGGER_PATH,
        """
        event: SCMPublishTag
        when:
          - repoId: { exact: qits-blobstore }
        steps: []
        """);
    String uuidPayload =
        "{\"repoId\":\"2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f\",\"projectId\":\"qits\","
            + "\"repoName\":\"qits-blobstore\",\"tagName\":\"2026.821.1\"}";

    deliver(arrival(UUID.randomUUID().toString(), "SCMPublishTag", uuidPayload));

    assertEquals(
        1,
        runService.runsFor(repoId).size(),
        "a file naming the repository must keep matching once the id becomes a UUID");
  }

  @Test
  public void aReleaseFileNamingTheRepositoryMatchesTheRealSCMReleasePayload() throws Exception {
    // The estate's own ci-event-release.yml, unedited, against the payload qits-workspaces really
    // publishes: `repository` is the storage UUID and `repositoryName` is the name the file spells.
    // This is the 2026-08-22 defect — five SCMRelease events and zero release pipelines — as a case.
    seedTrigger(
        ".config/qits/ci-event-release.yml",
        """
        event: SCMRelease
        when:
          - repository: { exact: qits-platform-maintenance }
        artifacts:
          - { type: docker, name: qits/qits-platform-maintenance }
        steps:
          - image: alpine:3
            script: echo publish
        """);
    String scmRelease =
        "{\"branch\":\"release/maintenance-service\","
            + "\"projectId\":\"b03b7c4e-1d2f-4a5b-8c9d-0e1f2a3b4c5d\","
            + "\"repository\":\"764e8bf9-3a2b-4c1d-9e8f-7a6b5c4d3e2f\","
            + "\"repositoryName\":\"qits-platform-maintenance\","
            + "\"version\":\"2026.822.170613\"}";

    deliver(arrival(UUID.randomUUID().toString(), "SCMRelease", scmRelease));

    assertEquals(
        1,
        runService.runsFor(repoId).size(),
        "a release file naming the repository must match once the id becomes a UUID");
    assertEquals("SCMRelease", runService.runsFor(repoId).get(0).triggerEventName);
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
    assertTrue(
        fakeConfig.triggerReads().contains(repoId + "@main#REPOSITORY"), "the candidate was asked");
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
    // A malformed arrival answers false, which the durable listener never sees: it checks the frame
    // before it gets here, so the only false it can read is a full queue.
    assertFalse(engine.onEvent(null));
    assertFalse(engine.onEvent(arrival(null, "BuildSuccessful", PAYLOAD)));
    assertFalse(engine.onEvent(arrival(UUID.randomUUID().toString(), null, PAYLOAD)));
    assertTrue(engine.onEvent(arrival(UUID.randomUUID().toString(), "BuildSuccessful", null)));
    assertTrue(
        engine.onEvent(arrival(UUID.randomUUID().toString(), "BuildSuccessful", "not json {")));
  }

  /**
   * The answer is about acceptance, not about the outcome — which is what makes it usable as the
   * durable listener's retry signal. An event whose payload no selection can match was still
   * <em>evaluated</em>, so it is accepted and settled; only a queue that had no room for it is a
   * {@code false}, and that is the one case worth being handed back later.
   */
  @Test
  public void anAcceptedEventAnswersTrueEvenWhenNothingMatchesIt() throws Exception {
    fakeCandidates.set();

    assertTrue(engine.onEvent(arrival(UUID.randomUUID().toString(), "NobodyListens", PAYLOAD)));
    engine.awaitIdle();
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

  // --- evaluateNow: what a caller who cannot be redelivered to is owed ---

  /**
   * The manual trigger's whole contract in one assertion: when it returns, the rows are there and it
   * says which. {@code onEvent} can only promise that something was queued.
   */
  @Test
  public void evaluateNowAnswersWithTheRunsItRecorded() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    String eventId = UUID.randomUUID().toString();

    CiEventTriggerService.Evaluation done =
        engine.evaluateNow(arrival(eventId, "BuildSuccessful", PAYLOAD));

    assertTrue(done.answered());
    assertEquals(1, done.repositoriesRead());
    assertEquals(List.of(), done.repositoriesSkipped());
    assertEquals(1, done.runIds().size());
    // No waiting for a worker: the row the caller was told about exists as the call returns.
    assertEquals(done.runIds(), runService.runsFor(repoId).stream().map(r -> r.id).toList());
    runService.awaitIdle();
  }

  @Test
  public void evaluateNowRecordsNothingWhenNothingSelectsTheEvent() {
    seedTrigger(TRIGGER_PATH, TRIGGER);

    CiEventTriggerService.Evaluation done =
        engine.evaluateNow(arrival(UUID.randomUUID().toString(), "NobodyListens", PAYLOAD));

    // Empty runs AND nothing skipped is the only shape that means "asked everybody, matched none" —
    // which is why the endpoint answers with both lists rather than with a run count.
    assertTrue(done.answered());
    assertEquals(List.of(), done.runIds());
    assertEquals(List.of(), done.repositoriesSkipped());
  }

  /**
   * A repository that could not be read is not a repository that said no, and conflating the two is
   * how "nothing matched" becomes a lie about an unreachable git host.
   */
  @Test
  public void aCandidateThatCannotBeReadIsReportedRatherThanCountedAsNoMatch() {
    fakeConfig.putTriggersUnreachable(repoId, CiEventTriggerService.TRIGGER_BRANCH);

    CiEventTriggerService.Evaluation done =
        engine.evaluateNow(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));

    assertFalse(done.answered(), "nothing answered, so there is no answer — the endpoint 503s");
    assertEquals(0, done.repositoriesRead());
    assertEquals(List.of(repoId), done.repositoriesSkipped());
    assertEquals(List.of(), done.runIds());
  }

  @Test
  public void anEmptyCandidateSetIsNotAnAnswerEither() {
    fakeCandidates.set();

    CiEventTriggerService.Evaluation done =
        engine.evaluateNow(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD));

    assertFalse(done.answered(), "qits-ci knows no repository, so it cannot say the event matched none");
  }

  /**
   * <b>The 2026-08-10 loss, and the reason this endpoint no longer shares a queue with the bus.</b>
   *
   * <p>The trigger worker is one thread in front of a bounded queue. Stick it — inside a git-host
   * read, which is where it spends its time — and two things follow, both of them silent: the queue
   * fills, and everything in it waits. A caller-supplied event handed to that queue is lost, because
   * unlike a bus frame it is on no log, holds no claim, and nothing will ever offer it again. That
   * is what happened: a 2xx, no run, and no line at any level for thirty minutes.
   *
   * <p>So the manual path evaluates on the caller's own thread. This stages the worst state the
   * worker can be in — wedged AND its queue refusing — and asserts a manual evaluation still records
   * its run and says so.
   */
  @Test
  public void aWedgedWorkerAndAFullQueueCannotSwallowAManualEvaluation() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    fakeCandidates.wedgeTheTriggerWorker();
    try {
      // One arrival to occupy the worker. The fillers select nothing, so releasing them later costs
      // one config read each and no runs.
      assertTrue(engine.onEvent(arrival(UUID.randomUUID().toString(), "NobodyListens", PAYLOAD)));
      fakeCandidates.awaitTriggerWorkerWedged();

      boolean refused = false;
      for (int i = 0; i < CiEventTriggerService.QUEUE_CAPACITY + 2 && !refused; i++) {
        refused = !engine.onEvent(arrival(UUID.randomUUID().toString(), "NobodyListens", PAYLOAD));
      }
      assertTrue(refused, "the bounded queue refuses once it is full — the bus path's retry signal");

      String eventId = UUID.randomUUID().toString();
      CiEventTriggerService.Evaluation done =
          engine.evaluateNow(arrival(eventId, "BuildSuccessful", PAYLOAD));

      assertEquals(1, done.runIds().size(), "evaluated beside the wedge, not behind it");
      forgetLoadedEntities();
      List<CiRun> recorded = runService.runsFor(repoId);
      assertEquals(1, recorded.size());
      assertEquals(eventId, recorded.get(0).triggerEventId);
    } finally {
      fakeCandidates.freeTheTriggerWorker();
      awaitEvaluatorDrained();
      runService.awaitIdle();
    }
  }

  // --- the project scope: what a machine caller granted ONE project may have evaluated ---

  /**
   * <b>The manual trigger's guard, from the engine's side.</b> The door used to demand {@code
   * project=*} because qits-ci could not name a project; a candidate carries one now, so a
   * project-scoped caller is admitted and the evaluation is narrowed to that project's repositories.
   *
   * <p>What makes it a guard rather than a filter: the other project's repository is never asked, so
   * there is no path from a scoped call to a run in it — refused by construction rather than by a
   * check somebody has to remember to write.
   */
  @Test
  public void aProjectScopedEvaluationAsksOnlyThatProjectsRepositories() throws Exception {
    String foreignRepoId = "foreign-" + UUID.randomUUID().toString().substring(0, 8);
    CiRepoRef mine = CiRepoRef.of(repoId, "project-alpha", "alpha-consumer");
    CiRepoRef theirs = CiRepoRef.of(foreignRepoId, "project-beta", "beta-consumer");
    fakeCandidates.setRefs(mine, theirs);
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));
    fakeConfig.putTriggers(foreignRepoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));

    CiEventTriggerService.Evaluation done =
        engine.evaluateNow(
            arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD), "project-alpha");

    assertEquals(1, done.repositoriesRead(), "one project, one candidate asked");
    assertEquals(1, done.runIds().size());
    runService.awaitIdle();
    forgetLoadedEntities();
    assertEquals(1, runService.runsFor(repoId).size());
    assertEquals(
        List.of(),
        runService.runsFor(foreignRepoId),
        "a repository of another project is not evaluated, so it cannot run");
  }

  @Test
  public void anUnscopedEvaluationStillAsksEverybody() throws Exception {
    String otherRepoId = "other-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.setRefs(
        CiRepoRef.of(repoId, "project-alpha", "alpha-consumer"),
        CiRepoRef.of(otherRepoId, "project-beta", "beta-consumer"));
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));
    fakeConfig.putTriggers(otherRepoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));

    CiEventTriggerService.Evaluation done =
        engine.evaluateNow(arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD), null);

    assertEquals(2, done.repositoriesRead(), "null scope is every project, exactly as before");
    assertEquals(2, done.runIds().size());
    runService.awaitIdle();
  }

  /**
   * A scope this instance can place no repository in is a <b>refusal</b>, not an empty answer: the
   * catalogue is not empty, so "nothing matched" would be a statement about the event when the truth
   * is a statement about the caller. The endpoint turns it into a 403.
   */
  @Test
  public void aScopeNoCandidateBelongsToIsRefused() {
    fakeCandidates.setRefs(CiRepoRef.of(repoId, "project-alpha", "alpha-consumer"));
    seedTrigger(TRIGGER_PATH, TRIGGER);

    CiEventTriggerService.NoRepositoriesInProject refused =
        assertThrows(
            CiEventTriggerService.NoRepositoriesInProject.class,
            () ->
                engine.evaluateNow(
                    arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD),
                    "project-beta"));

    assertEquals("project-beta", refused.project());
    assertEquals(List.of(), runService.runsFor(repoId));
  }

  /**
   * The fail-closed half. A candidate qits-ci knows only from its own run rows carries no project,
   * and so does every candidate when the qits-projects listing is unreachable and the git host's
   * id-only listing is all there is. Unprovable is not "yours": a scoped caller reaches none of them,
   * where the alternative — treating an unnamed project as a match — would widen a scoped token to
   * the whole catalogue exactly when the platform is least able to say otherwise.
   */
  @Test
  public void aCandidateWhoseProjectIsUnknownIsInNoScope() {
    fakeCandidates.set(repoId);
    seedTrigger(TRIGGER_PATH, TRIGGER);

    assertThrows(
        CiEventTriggerService.NoRepositoriesInProject.class,
        () ->
            engine.evaluateNow(
                arrival(UUID.randomUUID().toString(), "BuildSuccessful", PAYLOAD), "project-alpha"));
  }

  // --- the owed-event ledger: an accepted event survives the process that accepted it ---

  /**
   * The acceptance is durable before it is reported, and settled when the evaluation returns.
   *
   * <p>Staged with the worker wedged, which is the only way to observe the middle of it: the row is
   * written by {@code onEvent} on the caller's thread and cleared by the evaluation on the trigger
   * worker, so parking the worker holds the ledger open.
   */
  @Test
  public void anAcceptedEventIsOwedUntilItHasBeenEvaluated() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    String eventId = UUID.randomUUID().toString();
    fakeCandidates.wedgeTheTriggerWorker();
    try {
      assertTrue(engine.onEvent(arrival(eventId, "BuildSuccessful", PAYLOAD)));
      fakeCandidates.awaitTriggerWorkerWedged();

      CiOwedEvent recorded = owedRow(eventId);
      assertNotNull(recorded, "the acceptance is on the record before the evaluation happens");
      assertEquals("BuildSuccessful", recorded.eventName);
      assertEquals(PAYLOAD, recorded.payload);
      assertEquals(List.of(), runService.runsFor(repoId), "and no run exists yet");
    } finally {
      fakeCandidates.freeTheTriggerWorker();
    }
    engine.awaitIdle();
    runService.awaitIdle();
    forgetLoadedEntities();

    assertEquals(1, runService.runsFor(repoId).size());
    assertNull(owedRow(eventId), "an evaluated event is owed by nobody");
  }

  /**
   * <b>The 2026-09-04 loss, and the whole reason the ledger exists.</b>
   *
   * <p>Three release requests created within a second of a qits-ci redeploy never got a QA run: the
   * dying instance claimed their {@code ReleaseRequestChanged}, the watermark moved past it, and the
   * evaluation that would have written the run row never ran. The claim is on the eventstream
   * datasource and the run row is on ci's, so the two cannot be one transaction — what can be, and
   * now is, is the acceptance.
   *
   * <p>This stages exactly that state — the acceptance committed, no run anywhere, the process that
   * would have evaluated it gone — and asserts the sweep a later process runs recovers it.
   */
  @Test
  public void anEventClaimedByADeadProcessIsRecoveredBySweepingWhatItLeftOwed() throws Exception {
    seedTrigger(RELEASE_REQUEST_PATH, RELEASE_REQUEST_TRIGGER);
    String eventId = UUID.randomUUID().toString();
    ownedByADeadProcess(eventId, "ReleaseRequestChanged");
    assertEquals(List.of(), runService.runsFor(repoId), "nothing ran: that is the loss");

    engine.sweepOwed(Instant.now());

    runService.awaitIdle();
    forgetLoadedEntities();
    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(1, recorded.size(), "the sweep is what the release request was waiting for");
    assertEquals(eventId, recorded.get(0).triggerEventId);
    assertNull(owedRow(eventId), "and it is not owed twice");
  }

  /**
   * The sweep is at-least-once and that is safe by construction: {@code unique (trigger_event_id,
   * repo_id, config_path)} refuses the second run. So a settle that was itself lost — the row still
   * there for an event whose runs exist — costs a fan-out and nothing else.
   */
  @Test
  public void sweepingAnEventThatAlreadyRanRecordsNoSecondRun() throws Exception {
    seedTrigger(TRIGGER_PATH, TRIGGER);
    String eventId = UUID.randomUUID().toString();
    deliver(arrival(eventId, "BuildSuccessful", PAYLOAD));
    assertEquals(1, runService.runsFor(repoId).size());
    ownedByADeadProcess(eventId, "BuildSuccessful");

    engine.sweepOwed(Instant.now());

    runService.awaitIdle();
    forgetLoadedEntities();
    assertEquals(1, runService.runsFor(repoId).size(), "the dedupe holds, so the replay is a no-op");
    assertNull(owedRow(eventId));
  }

  @Test
  public void aSweepWithNothingOwedDoesNothingAtAll() {
    seedTrigger(TRIGGER_PATH, TRIGGER);

    engine.sweepOwed(Instant.now());

    assertEquals(List.of(), runService.runsFor(repoId));
  }

  /** The state a process that died between the claim and the run leaves in ci's own store. */
  private void ownedByADeadProcess(String eventId, String eventName) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              owedEvents.record(
                  eventId, eventName, Instant.parse("2026-07-31T12:46:03Z"), PAYLOAD);
              // Aged past any grace, which is what makes it the periodic sweep's business as well as
              // the boot sweep's.
              owedEvents.findById(eventId).acceptedAt = Instant.now().minus(Duration.ofMinutes(30));
            });
  }

  private CiOwedEvent owedRow(String eventId) {
    return QuarkusTransaction.requiringNew().call(() -> owedEvents.findById(eventId));
  }

  /**
   * {@code awaitIdle} submits, so it cannot be called while the queue is still full — the drain has
   * to be waited for before it can be waited on.
   */
  private void awaitEvaluatorDrained() throws Exception {
    for (int attempt = 0; attempt < 300; attempt++) {
      try {
        engine.awaitIdle();
        return;
      } catch (RejectedExecutionException stillFull) {
        Thread.sleep(100);
      }
    }
    throw new IllegalStateException("the trigger queue never drained");
  }
}
