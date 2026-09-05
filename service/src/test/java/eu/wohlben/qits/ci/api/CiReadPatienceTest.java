package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiDaemonPin;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiDaemonPinRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>A read that loses its connection mid-flight is held and served; one whose database stays gone
 * is still a 500.</b>
 *
 * <p>The pool opens every connection through {@code PatientPgDriver}, so a request that has executed
 * nothing waits for postgres to come back. This is the other half — a connection severed <i>after</i>
 * the statements ran, which no patience at creation time can undo — and it is why the caller-facing
 * reads in {@code CiRunService} and {@code CiDaemonPins} are wrapped in {@code DbRetry}. The
 * incident shape is a postgres cutover mid-poll: qits-spa-ci polls {@code /ci/api/runs/active}
 * every few seconds and the container healthcheck reads the daemon pin ladder on its own cadence, so
 * both surfaces sit exactly where a severed pool is most likely to be found.
 *
 * <p><b>Patience precedes the honest failure; it does not replace it.</b> What is under the deadline
 * is served, what outlives it is a 500, and the second case is here so the first cannot pass by
 * catching failures.
 *
 * <p>The fault is installed as a {@link QuarkusMock} over the Panache repository, the one bean
 * between the service and the database. Mocking it rather than breaking the datasource keeps the
 * fault to a single test: a datasource that cannot connect would take the whole process with it. The
 * deadline is shortened for the suite (see {@code src/test/resources/application.properties}), so the
 * give-up case does not wait out a production cutover.
 */
@QuarkusTest
public class CiReadPatienceTest {

  private static final String ACTIVE_RUNS = "/ci/api/runs/active";
  private static final String DAEMON = "/ci/api/daemon";
  private static final String REPO = "read-patience-repo";

  @Inject CiRunRepository runs;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(() -> runs.delete("repoId = ?1", REPO));
  }

  /**
   * What a severed pool throws: unchecked, like Hibernate's {@code JDBCConnectionException}, over a
   * SQLState {@code 08} cause. The cause is what makes it a <b>connection</b> failure rather than a
   * failed statement, and therefore what {@code DbRetry} agrees to wait on — a business failure with
   * the same wording is rethrown at once, deliberately.
   */
  static IllegalStateException connectionLost() {
    return new IllegalStateException(
        "connection pool severed by a database cutover",
        new SQLTransientConnectionException("the connection attempt failed", "08001"));
  }

  /**
   * A run repository that loses its connection once and answers the next time — the cutover as a
   * request straddling it sees it.
   *
   * <p>It replays an answer captured from the real repository rather than querying again: the
   * instance is constructed by hand for {@link QuarkusMock}, and what this test is about is the
   * retry, not the query. Every method it does not override is the real one, which is what lets the
   * cleanup run through it.
   */
  public static class FlakyRunRepository extends CiRunRepository {

    private final List<CiRun> answer;
    private final AtomicInteger reads = new AtomicInteger();

    public FlakyRunRepository(List<CiRun> answer) {
      this.answer = answer;
    }

    @Override
    public List<CiRun> listActiveNewestFirst() {
      if (reads.incrementAndGet() == 1) {
        throw connectionLost();
      }
      return answer;
    }

    int reads() {
      return reads.get();
    }
  }

  /** A run repository whose connection pool is gone and stays gone. */
  public static class SeveredRunRepository extends CiRunRepository {

    @Override
    public List<CiRun> listActiveNewestFirst() {
      throw connectionLost();
    }
  }

  /** The daemon ladder's half of the same fault. */
  public static class FlakyPinRepository extends CiDaemonPinRepository {

    private final AtomicInteger reads = new AtomicInteger();

    @Override
    public List<CiDaemonPin> listNewestFirst() {
      if (reads.incrementAndGet() == 1) {
        throw connectionLost();
      }
      return List.of();
    }

    int reads() {
      return reads.get();
    }
  }

  @Test
  public void anActiveRunListingWhoseDatabaseComesBackIsWaitedFor() {
    // The incident's own request shape, with the outage short enough to survive: a run that exists,
    // whose first read is severed mid-flight. Held, then served — not a 500 and not a short list.
    String runId = seedQueuedRun();
    FlakyRunRepository flaky =
        new FlakyRunRepository(QuarkusTransaction.requiringNew().call(runs::listActiveNewestFirst));
    QuarkusMock.installMockForType(flaky, CiRunRepository.class);

    given().when().get(ACTIVE_RUNS).then().statusCode(200).body("runs.id", hasItem(runId));

    assertTrue(flaky.reads() > 1, "the severed read was not retried at all");
  }

  @Test
  public void anActiveRunListingWhoseDatabaseStaysGoneIs5xx() {
    QuarkusMock.installMockForType(new SeveredRunRepository(), CiRunRepository.class);

    given().when().get(ACTIVE_RUNS).then().statusCode(500);
  }

  @Test
  public void theDaemonPinLadderIsWaitedForToo() {
    // GET /ci/api/daemon and the readiness check read this ladder on a healthcheck's cadence, so a
    // cutover finds them mid-read more often than it finds anything else here.
    FlakyPinRepository flaky = new FlakyPinRepository();
    QuarkusMock.installMockForType(flaky, CiDaemonPinRepository.class);

    given().when().get(DAEMON).then().statusCode(200);

    assertTrue(flaky.reads() > 1, "the severed ladder read was not retried at all");
  }

  private String seedQueuedRun() {
    String runId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = REPO;
              run.branch = "main";
              run.commitSha = "0".repeat(40);
              run.status = CiRunStatus.QUEUED;
              run.triggerType = CiTriggerType.EVENT;
              run.configPath = ".config/qits/ci-event-suite.yml";
              run.createdAt = Instant.now();
              runs.persist(run);
            });
    return runId;
  }
}
