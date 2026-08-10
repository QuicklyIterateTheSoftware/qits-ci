package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>One event, one trigger file, at most one run — ever.</b> The guarantee is the unique constraint
 * on {@code (trigger_event_id, repo_id, config_path)} rather than an application check, because what
 * it has to survive is a redelivery, a race and a restart. This class holds it from both sides: the
 * engine's behaviour on a second arrival, and the constraint itself against the database.
 *
 * <p><b>It covers pushes too now, and that changed which half is the interesting one.</b> A push run
 * carries the id of the {@code SCMPublishCommit} that announced it — that is what gives the run a
 * causation parent — so the constraint means "one run per announced push" as well as "one run per
 * (event, trigger file)". A push's config path is the constant {@code ci-post-receive.yml}, so the
 * event id is the whole of what tells one push row from the next, which is exactly right: two runs
 * for one announcement are two builds of one commit.
 *
 * <p>{@code NULL trigger_event_id} therefore no longer describes every push. It describes a run
 * nothing announced — {@code CiRunService.execute}, the synchronous test entry — and the null
 * behaviour is still pinned below, because it is what makes such rows all distinct instead of
 * colliding on the second one. SQL says rows are duplicates only when all corresponding values are
 * non-null and equal; this pins that the database agrees, rather than trusting it. Plain {@code
 * unique}, never postgres' {@code nulls not distinct}.
 */
@QuarkusTest
public class CiEventTriggerDedupeTest extends CiTestSupport {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-upstream.yml";

  private static final String TRIGGER =
      """
      event: BuildSuccessful
      steps:
        - image: alpine:3
          script: echo bump
      """;

  private static final String HEAD = "e".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;

  private String repoId;

  @BeforeEach
  void seed() {
    repoId = "consumer-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));
  }

  private void deliver(String eventId) throws Exception {
    engine.evaluate(
        new CiEventTriggerService.Arrival(
            eventId, "BuildSuccessful", Instant.now(), "{\"repoId\":\"upstream\"}"));
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  // --- what the engine does ---

  @Test
  public void aRedeliveredEventRecordsNoSecondRun() throws Exception {
    // Bus replays are legal — the PUT is idempotent and the future catch-up feature will redeliver
    // deliberately — so the same eventId arriving twice must be dropped, not re-run.
    String eventId = UUID.randomUUID().toString();
    deliver(eventId);
    deliver(eventId);
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void aDifferentEventOfTheSameNameDoesRunAgain() throws Exception {
    // The constraint kills replays, not descendants — which is exactly why it is no loop guard.
    deliver(UUID.randomUUID().toString());
    deliver(UUID.randomUUID().toString());
    assertEquals(2, runService.runsFor(repoId).size());
  }

  // --- what the database does, which is the actual guarantee ---

  @Test
  public void theConstraintRefusesASecondRowForOneEventAndTriggerFile() {
    String eventId = UUID.randomUUID().toString();
    insertEventRun(eventId, TRIGGER_PATH);
    // Not "a run was not recorded" but "the database would not have it" — the property that holds
    // when two processes race, which no read-then-write can promise.
    assertThrows(RuntimeException.class, () -> insertEventRun(eventId, TRIGGER_PATH));
    assertEquals(1, runService.runsFor(repoId).size());
  }

  @Test
  public void oneEventReachingTwoTriggerFilesIsTwoRows() {
    String eventId = UUID.randomUUID().toString();
    insertEventRun(eventId, ".config/qits/ci-event-a.yml");
    insertEventRun(eventId, ".config/qits/ci-event-b.yml");
    assertEquals(2, runService.runsFor(repoId).size());
  }

  @Test
  public void oneEventReachingTwoRepositoriesIsTwoRows() {
    String eventId = UUID.randomUUID().toString();
    String other = "other-" + UUID.randomUUID().toString().substring(0, 8);
    insertEventRun(repoId, eventId, TRIGGER_PATH);
    insertEventRun(other, eventId, TRIGGER_PATH);
    assertEquals(1, runService.runsFor(repoId).size());
    assertEquals(1, runService.runsFor(other).size());
  }

  @Test
  public void nullTriggerEventIdRowsAreAllDistinctToTheConstraint() {
    // A run nothing announced carries a null here and the same repo_id and config_path as the last
    // one; read as duplicates, the second such row would fail to insert. Pushes are named by their
    // announcing event now and no longer rest on this, but the behaviour is the constraint's own
    // and a migration could still take it away.
    for (int i = 0; i < 5; i++) {
      insertPostReceiveRun();
    }
    List<CiRun> recorded = runService.runsFor(repoId);
    assertEquals(5, recorded.size());
    assertTrue(
        recorded.stream()
            .allMatch(
                run ->
                    run.triggerEventId == null
                        && run.triggerType == CiTriggerType.POST_RECEIVE
                        && CiConfigParser.CONFIG_PATH.equals(run.configPath)));
  }

  private void insertEventRun(String eventId, String configPath) {
    insertEventRun(repoId, eventId, configPath);
  }

  private void insertEventRun(String repo, String eventId, String configPath) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = row(repo);
              run.triggerType = CiTriggerType.EVENT;
              run.triggerEventId = eventId;
              run.triggerEventName = "BuildSuccessful";
              run.configPath = configPath;
              runs.persist(run);
            });
  }

  private void insertPostReceiveRun() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = row(repoId);
              run.triggerType = CiTriggerType.POST_RECEIVE;
              run.configPath = CiConfigParser.CONFIG_PATH;
              runs.persist(run);
            });
  }

  private CiRun row(String repo) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repo;
    run.branch = "main";
    run.commitSha = HEAD;
    run.status = CiRunStatus.SUCCESS;
    run.createdAt = Instant.now();
    run.finishedAt = run.createdAt;
    return run;
  }
}
