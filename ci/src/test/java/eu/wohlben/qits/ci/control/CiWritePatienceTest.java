package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A request-path write whose connection is severed mid-flight is run again, once, and lands
 * exactly one row.</b>
 *
 * <p>{@code PatientPgDriver} holds a request that has executed <em>nothing</em>; this is the other
 * half — a connection lost after the statements ran — and for a write it needs {@code
 * DbRetry.inNewTx}, which owns the transaction boundary and so can tell an attempt that certainly
 * did not commit from one whose outcome is unknowable. {@code CiReadPatienceTest} is this file's
 * read-side twin, over HTTP.
 *
 * <p><b>The fault is installed on {@code flush()}</b>, which is precisely where the wrapped bodies
 * put their writes: an ORM flushes at commit by default, and a loss there is a lost commit
 * acknowledgement that must be reported rather than retried. Each wrapped body calls {@code flush}
 * explicitly to move its statements to the retriable side of that line, so a fault there is the real
 * shape of the incident and not an approximation of it. Mocking the repository rather than breaking
 * the datasource keeps the fault inside one test method.
 *
 * <p>The two claims that matter are here together on purpose. A connection loss is <b>waited out and
 * its effect happens once</b>; anything else is <b>not retried at all</b>, because a business failure
 * is as certain not to have committed as it is to fail the same way for fifteen seconds.
 */
@QuarkusTest
public class CiWritePatienceTest extends CiTestSupport {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-upstream.yml";

  private static final String TRIGGER =
      """
      event: BuildSuccessful
      steps:
        - image: alpine:3
          script: echo bump
      """;

  private static final String HEAD = "a".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;

  private String repoId;

  @BeforeEach
  void seedTheRepository() {
    repoId = "written-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));
  }

  /**
   * What a severed pool throws: unchecked, over a SQLState {@code 08} cause. The cause is what makes
   * it a <b>connection</b> failure rather than a failed statement, and therefore the only class
   * {@code DbRetry} agrees to wait on.
   */
  private static IllegalStateException connectionLost() {
    return new IllegalStateException(
        "connection pool severed by a database cutover",
        new SQLTransientConnectionException("the connection attempt failed", "08001"));
  }

  /**
   * A run repository whose flush fails a given number of times and then works — the cutover as a
   * write straddling it sees it. Every method it does not override is the real one, which is what
   * lets the run row, the reads and the cleanup go to the real database around the fault.
   */
  public static class FlakyFlushRunRepository extends CiRunRepository {

    private final int failures;
    private final RuntimeException fault;
    private final AtomicInteger flushes = new AtomicInteger();

    public FlakyFlushRunRepository(int failures, RuntimeException fault) {
      this.failures = failures;
      this.fault = fault;
    }

    @Override
    public void flush() {
      if (flushes.incrementAndGet() <= failures) {
        throw fault;
      }
      super.flush();
    }

    int flushes() {
      return flushes.get();
    }
  }

  private CiEventTriggerService.Arrival arrival() {
    return new CiEventTriggerService.Arrival(
        UUID.randomUUID().toString(), "BuildSuccessful", Instant.now(), "{\"repoId\":\"upstream\"}");
  }

  private List<CiRun> recorded() {
    forgetLoadedEntities();
    return runService.runsFor(repoId);
  }

  @Test
  public void anEventRunAcceptedThroughACutoverIsRecordedExactlyOnce() throws Exception {
    // The accept is the check, the insert and the supersede in ONE transaction, so patience there
    // has to wrap the whole bracket — and a retried bracket must leave one row, not two.
    FlakyFlushRunRepository flaky = new FlakyFlushRunRepository(1, connectionLost());
    QuarkusMock.installMockForType(flaky, CiRunRepository.class);

    engine.evaluate(arrival());
    runService.awaitIdle();

    assertTrue(flaky.flushes() > 1, "the severed insert was not retried at all");
    List<CiRun> runs = recorded();
    assertEquals(1, runs.size(), "the retried accept inserted the run twice");
    assertEquals(CiTriggerType.EVENT, runs.get(0).triggerType);
  }

  @Test
  public void aFailureThatIsNotAConnectionLossIsNotRetried() throws Exception {
    // Same seam, a failure the second attempt would meet unchanged. One attempt, no row, and the
    // engine reports the repository as one it could not evaluate rather than as one that matched
    // nothing.
    FlakyFlushRunRepository broken =
        new FlakyFlushRunRepository(
            Integer.MAX_VALUE, new IllegalStateException("this insert is simply wrong"));
    QuarkusMock.installMockForType(broken, CiRunRepository.class);

    CiEventTriggerService.Evaluation evaluation = engine.evaluate(arrival());
    runService.awaitIdle();

    assertEquals(1, broken.flushes(), "a business failure was waited on");
    assertEquals(List.of(), evaluation.runIds());
    assertEquals(List.of(repoId), evaluation.repositoriesSkipped());
    assertEquals(List.of(), recorded(), "the failed attempt was rolled back");
  }

  @Test
  public void cancellingAQueuedRunThroughACutoverStillFinishesTheRow() {
    // The other wrapped write, and the only one a person is waiting on: cancel runs on the request
    // thread, outside any transaction of its own.
    String runId = seedQueuedRun();
    FlakyFlushRunRepository flaky = new FlakyFlushRunRepository(1, connectionLost());
    QuarkusMock.installMockForType(flaky, CiRunRepository.class);

    runService.cancel(runId, "stopping it");

    assertTrue(flaky.flushes() > 1, "the severed cancellation was not retried at all");
    forgetLoadedEntities();
    CiRun cancelled = runService.requireRun(runId);
    assertEquals(CiRunStatus.CANCELLED, cancelled.status);
    assertEquals("stopping it", cancelled.cancellationReason);
  }

  @Test
  public void aCancellationThatIsNotAConnectionLossFailsAtOnce() {
    String runId = seedQueuedRun();
    FlakyFlushRunRepository broken =
        new FlakyFlushRunRepository(
            Integer.MAX_VALUE, new IllegalStateException("this update is simply wrong"));
    QuarkusMock.installMockForType(broken, CiRunRepository.class);

    assertThrows(IllegalStateException.class, () -> runService.cancel(runId, "stopping it"));

    assertEquals(1, broken.flushes(), "a business failure was waited on");
    forgetLoadedEntities();
    assertEquals(CiRunStatus.QUEUED, runService.requireRun(runId).status);
  }

  private String seedQueuedRun() {
    String runId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = repoId;
              run.branch = "main";
              run.commitSha = HEAD;
              run.status = CiRunStatus.QUEUED;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = TEST_TRIGGER_PATH;
              run.createdAt = Instant.now();
              runs.persist(run);
            });
    return runId;
  }
}
