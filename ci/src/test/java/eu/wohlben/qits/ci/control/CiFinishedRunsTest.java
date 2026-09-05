package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.error.BadRequestException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>What CI just finished</b> — the complement of the active list, and the read behind {@code GET
 * /ci/api/runs/finished}.
 *
 * <p>Every row here is <b>inserted directly</b> rather than driven through the worker, and that is
 * the point of the class. The claims are about an ordering and a bound over a table, so a test that
 * pushed runs through the pipeline would be asserting them against whatever instants the worker
 * happened to stamp — which makes "newest" a race rather than a fact. Seeded rows let the ordering be
 * stated exactly, let a hundred-odd rows exist without a hundred-odd containers, and let {@code
 * QUEUED} and {@code RUNNING} sit in the table beside the finished ones with no worker to be occupied
 * by. The lifecycle that produces these statuses belongs to {@code CiRunServiceTest} and {@code
 * CiQueuedRunTest}; this class owns only what the listing does with them.
 */
@QuarkusTest
public class CiFinishedRunsTest extends CiTestSupport {

  @Inject CiRunService service;

  /** The instant the seeded rows count forward from, so every {@code createdAt} here is distinct. */
  private static final Instant EPOCH = Instant.parse("2026-07-31T09:00:00Z");

  @Test
  public void theDefaultIsTheNewestFiveAcrossEveryRepository() {
    // Seven runs over three repositories, so the answer cannot be "one repository's newest" and the
    // ordering has to be platform-wide. Inserted oldest-first; expected newest-first.
    List<String> oldestFirst = new ArrayList<>();
    for (int minute = 0; minute < 7; minute += 1) {
      oldestFirst.add(
          insert("repo-" + (minute % 3), CiRunStatus.SUCCESS, EPOCH.plusSeconds(minute * 60L)));
    }

    List<CiRun> finished = service.finishedRuns(null);

    assertEquals(
        CiRunService.DEFAULT_FINISHED_LIMIT,
        finished.size(),
        "no limit means the default, not every run on the instance");
    // The newest five, in newest-first order — the head of the same total ordering the repository
    // listing uses, so "the newest n" names the same n rows on every call.
    assertEquals(
        List.of(
            oldestFirst.get(6),
            oldestFirst.get(5),
            oldestFirst.get(4),
            oldestFirst.get(3),
            oldestFirst.get(2)),
        finished.stream().map(run -> run.id).toList());
    assertTrue(
        finished.stream().map(run -> run.repoId).distinct().count() > 1,
        "the listing is platform-wide, not one repository's");
  }

  @Test
  public void newestMeansFinishedLastNotAcceptedLast() {
    String slowOlder = insert("repo-slow", CiRunStatus.SUCCESS, EPOCH);
    String quickNewer = insert("repo-quick", CiRunStatus.SUCCESS, EPOCH.plusSeconds(60));
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runs.findById(slowOlder).finishedAt = EPOCH.plusSeconds(300);
              runs.findById(quickNewer).finishedAt = EPOCH.plusSeconds(120);
            });

    assertEquals(
        List.of(slowOlder, quickNewer),
        service.finishedRuns(2).stream().map(run -> run.id).toList(),
        "the pipeline that actually finished last belongs at the head");
  }

  @Test
  public void everyTerminalStatusCountsAsFinishedAndNothingInFlightDoes() {
    // CONFIG_ERROR is the one that gets forgotten when a predicate names the terminal statuses
    // instead of excluding the non-terminal ones — a broken gate is finished, and it is exactly the
    // outcome an operator most wants to see in a "what just happened" list.
    String green = insert("repo-a", CiRunStatus.SUCCESS, EPOCH);
    String red = insert("repo-a", CiRunStatus.FAILED, EPOCH.plusSeconds(60));
    String cancelled = insert("repo-b", CiRunStatus.CANCELLED, EPOCH.plusSeconds(120));
    String broken = insert("repo-b", CiRunStatus.CONFIG_ERROR, EPOCH.plusSeconds(180));
    // Both non-terminal statuses, and both NEWER than everything above — so a listing that leaked
    // them would leak them at the head, where the client reads first.
    String queued = insert("repo-c", CiRunStatus.QUEUED, EPOCH.plusSeconds(240));
    String running = insert("repo-c", CiRunStatus.RUNNING, EPOCH.plusSeconds(300));

    List<String> ids = service.finishedRuns(10).stream().map(run -> run.id).toList();

    assertEquals(List.of(broken, cancelled, red, green), ids, "every terminal status, newest first");
    assertTrue(ids.contains(cancelled), "a CANCELLED run has finished without being failed");
    assertTrue(ids.contains(broken), "a CONFIG_ERROR run has finished");
    assertFalse(ids.contains(queued), "a queued run has not finished");
    assertFalse(ids.contains(running), "a running run has not finished");
    // The two lists partition the table: what is not here is there, and nothing is in both.
    List<String> active = service.activeRuns().stream().map(run -> run.id).toList();
    assertEquals(List.of(running, queued), active);
    assertTrue(active.stream().noneMatch(ids::contains), "no run is both finished and in flight");
  }

  @Test
  public void theLimitBoundsTheAnswerAndAnOverLargeAskIsCappedRatherThanRefused() {
    // Five over the cap, so the clamp is measured rather than reasoned about: a listing that
    // honoured `limit` as asked would answer with all of them.
    int seeded = CiRunService.MAX_FINISHED_LIMIT + 5;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (int minute = 0; minute < seeded; minute += 1) {
                persist("repo-bulk", CiRunStatus.SUCCESS, EPOCH.plusSeconds(minute * 60L));
              }
            });

    assertEquals(2, service.finishedRuns(2).size(), "a limit under the row count is the bound");
    assertEquals(
        CiRunService.MAX_FINISHED_LIMIT,
        service.finishedRuns(1_000).size(),
        "an ask above the cap is answered with the cap, not refused and not honoured");
    assertEquals(
        CiRunService.MAX_FINISHED_LIMIT,
        service.finishedRuns(CiRunService.MAX_FINISHED_LIMIT).size(),
        "the cap itself is a legal ask");

    // A bound over a list that grows at the head is only a total answer because the ordering is:
    // the newest row must be the head whatever the bound was.
    assertEquals(
        service.finishedRuns(1).get(0).id,
        service.finishedRuns(50).get(0).id,
        "the head is the same row whatever the bound");
  }

  @Test
  public void aNonPositiveLimitIsACallerBugRatherThanAnEmptyAnswer() {
    insert("repo-a", CiRunStatus.SUCCESS, EPOCH);

    // The same rule runsFor(repoId, limit) applies, and it has to be the same one: two limits on one
    // surface that disagree about zero is a contract nobody can hold in their head.
    assertThrows(BadRequestException.class, () -> service.finishedRuns(0));
    assertThrows(BadRequestException.class, () -> service.finishedRuns(-1));
  }

  @Test
  public void anIdleInstanceAnswersWithAnEmptyListRatherThanFailing() {
    // Nothing seeded: a platform that has never finished a run is an ordinary state, and the client
    // draws "nothing yet" from an empty list.
    assertEquals(List.of(), service.finishedRuns(null));
  }

  /** One finished-or-not row, in its own transaction, with the instant the ordering turns on. */
  private String insert(String repoId, CiRunStatus status, Instant createdAt) {
    return QuarkusTransaction.requiringNew().call(() -> persist(repoId, status, createdAt));
  }

  private String persist(String repoId, CiRunStatus status, Instant createdAt) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.branch = "main";
    run.commitSha = "a".repeat(40);
    run.status = status;
    run.createdAt = createdAt;
    // A finished run has a finish; the two in-flight statuses do not, which is the row's own way of
    // saying the same thing the listing says.
    run.finishedAt =
        status == CiRunStatus.QUEUED || status == CiRunStatus.RUNNING ? null : createdAt.plusSeconds(30);
    run.triggerType = CiTriggerType.EVENT;
    run.configPath = TEST_TRIGGER_PATH;
    runs.persist(run);
    return run.id;
  }
}
