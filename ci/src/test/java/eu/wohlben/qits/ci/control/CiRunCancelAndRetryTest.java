package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.error.ConflictException;
import eu.wohlben.qits.ci.error.NotFoundException;
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
 * The two machine-guarded things a release request can have done to its CI: <b>withdraw</b> it, and
 * <b>re-ask</b> it.
 *
 * <p>Both exist because {@code ci_run.release_request_id} does. The commit those runs build is a
 * fold nobody pushed — the tip of {@code release/<id>}, rewritten by the next re-fold — so neither
 * qits-projects nor an operator has a sha that stays true or a run id that survives. The request id
 * is the handle, and these are the two operations that hold it.
 *
 * <p>Three properties are the point of the file, and each one is a way the feature could be quietly
 * wrong:
 *
 * <ul>
 *   <li><b>a cancellation is scoped to one request in one repository</b> — a sibling request open
 *       against the same repository must keep building, or withdrawing one request takes down work
 *       nobody withdrew;
 *   <li><b>a cancelled run publishes no verdict at all</b> — the release gate on the other side
 *       reads {@code BuildFailed} as "this commit is not releasable", and a withdrawn question
 *       answered that way would hold a commit for a build somebody stopped on purpose;
 *   <li><b>a retry is a second run of the same work</b>, past the dedupe that exists to refuse
 *       exactly that shape, and carrying the same request id, the same sha and the same cause — so
 *       its verdict correlates as if the first run had gone the other way.
 * </ul>
 *
 * <p>Queued states are staged against a <b>genuinely occupied worker</b>, {@code CiQueuedRunTest}'s
 * arrangement: the run worker is single-threaded, so parking one run inside its first step is what
 * makes the next ones queue, at an instant the test controls rather than one it hopes to catch.
 */
@QuarkusTest
public class CiRunCancelAndRetryTest extends CiTestSupport {

  private static final String QA_PATH = ".config/qits/ci-event-release-request.yml";

  /** The reference QA file's shape: a gating build, then a non-gating publish. */
  private static final String QA_TRIGGER =
      """
      event: ReleaseRequestChanged
      checkout:
        branch: backingBranch
        sha: mergedSha
      steps:
        - image: alpine:3
          script: ./mvnw verify
        - image: alpine:3
          gating: false
          script: ./publish-userflows.sh
      """;

  private static final String MERGED = "b".repeat(40);

  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;

  /** Opened by the test, awaited on the worker inside the blocking run's first step. */
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void forgetAnnouncements() {
    // The announcer is a @Mock rather than one of CiTestSupport's fakes, so it is this file's to
    // reset — and every assertion below is about what was or was not announced.
    announcer.reset();
  }

  @AfterEach
  void releaseTheWorker() throws Exception {
    release.countDown();
    service.awaitIdle();
  }

  // --- withdrawing a release request -------------------------------------------------------------

  @Test
  public void cancellingOneRequestsRunsLeavesASiblingRequestAndAnotherRepositoryAlone()
      throws Exception {
    occupyTheWorker();
    String repo = "consumer-" + UUID.randomUUID();
    String sibling = "consumer-" + UUID.randomUUID();

    String withdrawn = accept(repo, "rr-a");
    String otherRequest = accept(repo, "rr-b");
    String otherRepo = accept(sibling, "rr-a");

    List<String> stopped = service.cancelReleaseRequestRuns(repo, "rr-a");

    assertEquals(List.of(withdrawn), stopped, "only this repository's runs for this request");
    forgetLoadedEntities();
    CiRun cancelled = service.requireRun(withdrawn);
    assertEquals(CiRunStatus.CANCELLED, cancelled.status);
    assertEquals(CiRunService.RELEASE_REQUEST_CANCELLED, cancelled.cancellationReason);
    assertNull(cancelled.startedAt, "a run cancelled in the queue never started");

    // The two halves of the key, each proved to be load-bearing: one request folds many
    // repositories, and one repository carries many open requests.
    assertEquals(
        CiRunStatus.QUEUED,
        service.requireRun(otherRequest).status,
        "another request open against the same repository is not this cancellation's business");
    assertEquals(
        CiRunStatus.QUEUED,
        service.requireRun(otherRepo).status,
        "the same request in another repository is folded separately and builds separately");
  }

  @Test
  public void aCancelledQueuedRunNeverStartsAndAnnouncesNoVerdictEitherWay() throws Exception {
    occupyTheWorker();
    String repo = "consumer-" + UUID.randomUUID();
    String withdrawn = accept(repo, "rr-a");

    service.cancelReleaseRequestRuns(repo, "rr-a");
    release.countDown();
    service.awaitIdle();
    forgetLoadedEntities();

    assertEquals(CiRunStatus.CANCELLED, service.requireRun(withdrawn).status);
    assertEquals(0, service.stepsFor(withdrawn).size(), "a run that never started has no steps");
    assertFalse(
        fakeRunner.executed().stream().anyMatch(spec -> spec.runId().equals(withdrawn)),
        "the worker's claim reads the status back and drops a run that is no longer QUEUED");

    // The property qits-projects' release gate depends on. Not "announced as green" and not
    // "announced as red" — announced as nothing, because a withdrawn question has no answer.
    assertTrue(
        announcer.announced().stream().noneMatch(a -> a.runId().equals(withdrawn)),
        "a cancelled run is not a passing build");
    assertTrue(
        announcer.failed().stream().noneMatch(f -> f.runId().equals(withdrawn)),
        "and it is not a failing one either — the gate must never read it as a verdict");
  }

  @Test
  public void cancellingARequestWithNothingInFlightIsAnEmptyAnswerRatherThanAFailure()
      throws Exception {
    String repo = "consumer-" + UUID.randomUUID();
    String finished = accept(repo, "rr-a");
    service.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(finished).status);

    // The caller asked for a state — this request's work is not running — and that state holds. So
    // repeating the call is safe, which is what makes it usable from a retried outbound hop.
    assertEquals(List.of(), service.cancelReleaseRequestRuns(repo, "rr-a"));
    assertEquals(List.of(), service.cancelReleaseRequestRuns(repo, "never-existed"));
    assertEquals(CiRunStatus.SUCCESS, service.requireRun(finished).status, "and it changed nothing");
  }

  // --- re-asking the same question ---------------------------------------------------------------

  @Test
  public void aRetryIsANewRunOfTheSameWorkThatTheDedupeWouldHaveRefused() throws Exception {
    String repo = "consumer-" + UUID.randomUUID();
    fakeRunner.script(0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "flake"));
    String eventId = UUID.randomUUID().toString();
    String original = accept(repo, "rr-a", eventId);
    service.awaitIdle();
    forgetLoadedEntities();
    assertEquals(CiRunStatus.FAILED, service.requireRun(original).status);

    // The same event, offered again, is the replay the constraint refuses — which is exactly why a
    // retry cannot simply re-accept it, and why this assertion belongs beside the retry.
    assertNull(
        service.onEventTrigger(eventRun(repo, "rr-a", eventId)),
        "the dedupe still refuses a second run for one event");

    fakeRunner.reset();
    CiRun retry = service.retry(original);
    service.awaitIdle();
    forgetLoadedEntities();

    assertNotEquals(original, retry.id, "a retry is a new run, never a rewritten row");
    assertEquals(2, service.runsFor(repo).size());
    CiRun refired = service.requireRun(retry.id);
    assertEquals(original, refired.retryOfRunId, "and it says which run it re-fires");

    // Same work, so the verdict correlates exactly as the first one's would have.
    assertEquals(MERGED, refired.commitSha, "the retry builds the sha the original built");
    assertEquals("release/rr-a", refired.branch);
    assertEquals("rr-a", refired.releaseRequestId);
    assertEquals(QA_PATH, refired.configPath);

    // The bypass: a synthetic, unmistakably local trigger identity, unique by construction.
    assertTrue(
        refired.triggerEventId.startsWith(CiRunService.RETRY_TRIGGER_PREFIX),
        "a retry names no event qits-events minted: " + refired.triggerEventId);
    assertNotEquals(eventId, refired.triggerEventId);

    assertEquals(CiRunStatus.SUCCESS, refired.status, "and it really ran, on a runner that is green");
  }

  @Test
  public void aRetriedRunsVerdictHangsOffTheEventThatStartedTheWholeThing() throws Exception {
    // The causation edge is the reason the synthetic trigger id is not simply announced: a retry's
    // BuildSuccessful must name the domain event the original run was fired by, or a re-fired
    // release request becomes an unexplained root in the event log.
    String repo = "consumer-" + UUID.randomUUID();
    String eventId = UUID.randomUUID().toString();
    String original = accept(repo, "rr-a", eventId);
    service.awaitIdle();
    forgetLoadedEntities();

    CiRun retry = service.retry(original);
    service.awaitIdle();

    FakeRunAnnouncer.Announced announced =
        announcer.announced().stream()
            .filter(a -> a.runId().equals(retry.id))
            .findFirst()
            .orElseThrow();
    assertEquals(eventId, announced.triggerEventId(), "the cause is inherited, not re-minted");
    assertEquals(MERGED, announced.commitSha());
  }

  @Test
  public void aRetryIsWorthWhatTheFileDeclaresRatherThanWhatTheLastVerdictWasWorth()
      throws Exception {
    // The row's `gating` on a FINISHED run is what that run's verdict was worth — the file's flag
    // ANDed with the failing step's. Copying it onto the retry would start a re-run of a gating
    // pipeline off as non-gating, and a green one would then announce a verdict no release gate
    // holds a commit for.
    String repo = "consumer-" + UUID.randomUUID();
    fakeRunner.script(1, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    String original = accept(repo, "rr-a");
    service.awaitIdle();
    forgetLoadedEntities();
    assertFalse(service.requireRun(original).gating, "the non-gating half is what went red");

    fakeRunner.reset();
    CiRun retry = service.retry(original);
    service.awaitIdle();
    forgetLoadedEntities();

    assertTrue(service.requireRun(retry.id).gating, "the file is gating, and the retry re-reads it");
    assertTrue(
        announcer.announced().stream()
            .filter(a -> a.runId().equals(retry.id))
            .findFirst()
            .orElseThrow()
            .gating(),
        "and the verdict it publishes says so");
  }

  @Test
  public void aRunThatHasNotFinishedIsNotRetryableAndAnUnknownOneIsNotFound() throws Exception {
    occupyTheWorker();
    String repo = "consumer-" + UUID.randomUUID();
    String queued = accept(repo, "rr-a");

    // The question is still being answered; two runs racing for one verdict is not what was asked.
    assertThrows(ConflictException.class, () -> service.retry(queued));
    assertThrows(NotFoundException.class, () -> service.retry("no-such-run"));
    assertEquals(1, service.runsFor(repo).size(), "a refused retry records nothing");
  }

  @Test
  public void aCancelledRunIsRetryableAndARetryIsItselfRetryable() throws Exception {
    // Everything terminal is fair game: a re-fire is precisely what a cancellation invites, and the
    // synthetic identity is unique per retry rather than per source run, so retrying twice is two
    // runs rather than a constraint violation.
    occupyTheWorker();
    String repo = "consumer-" + UUID.randomUUID();
    String original = accept(repo, "rr-a");
    service.cancelReleaseRequestRuns(repo, "rr-a");
    release.countDown();
    service.awaitIdle();
    forgetLoadedEntities();

    CiRun first = service.retry(original);
    service.awaitIdle();
    forgetLoadedEntities();
    CiRun second = service.retry(first.id);
    service.awaitIdle();
    forgetLoadedEntities();

    assertEquals(3, service.runsFor(repo).size());
    assertEquals(first.id, service.requireRun(second.id).retryOfRunId, "a chain, not a star");
    assertEquals("rr-a", service.requireRun(second.id).releaseRequestId);
  }

  // --- fixture -----------------------------------------------------------------------------------

  /**
   * Accepts a run that parks inside its first step until {@link #release}, so everything accepted
   * after this call is genuinely queued. It uses a repository of its own, so it never appears in a
   * listing an assertion reads.
   */
  private void occupyTheWorker() throws Exception {
    String repo = "blocker-" + UUID.randomUUID();
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
    accept(repo, "rr-blocker");
    inStepZero.get(20, TimeUnit.SECONDS);
  }

  private String accept(String repoId, String requestId) {
    return accept(repoId, requestId, UUID.randomUUID().toString());
  }

  private String accept(String repoId, String requestId, String eventId) {
    return service.onEventTrigger(eventRun(repoId, requestId, eventId));
  }

  /**
   * One matched {@code ReleaseRequestChanged}, as {@code CiEventTriggerService} resolves it: the
   * backing branch and the merged sha already taken off the payload by {@code checkout:}, and the
   * payload itself preserved on the row.
   */
  private CiRunService.EventRun eventRun(String repoId, String requestId, String eventId) {
    return new CiRunService.EventRun(
        CiRepoRef.of(repoId, "qits", "qits-ci-service"),
        "release/" + requestId,
        MERGED,
        new CiEventTrigger(
            QA_PATH,
            CiRunService.RELEASE_REQUEST_EVENT_NAME,
            null, // the selection already matched; nothing below this seam reads it
            new CiPipeline(
                List.of(
                    new CiPipeline.CiStepDecl(
                        "alpine:3", "./mvnw verify", null, false, "", true, List.of()),
                    new CiPipeline.CiStepDecl(
                        "alpine:3", "./publish-userflows.sh", null, false, "", false, List.of()))),
            List.of(), // declares no artifact: a QA run announces a build and nothing more
            true,
            new CiEventTrigger.Checkout("backingBranch", "mergedSha")),
        eventId,
        CiRunService.RELEASE_REQUEST_EVENT_NAME,
        Instant.parse("2026-09-03T09:07:06Z"),
        "{\"releaseRequestId\":\""
            + requestId
            + "\",\"backingBranch\":\"release/"
            + requestId
            + "\",\"mergedSha\":\""
            + MERGED
            + "\"}",
        QA_TRIGGER);
  }
}
