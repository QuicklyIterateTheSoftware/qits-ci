package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.FakeDaemonProbe;
import eu.wohlben.qits.ci.entity.CiDaemonPin;
import eu.wohlben.qits.ci.entity.CiDaemonPinVerdict;
import eu.wohlben.qits.ci.persistence.CiDaemonPinRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fix 3: {@code GET /ci/api/daemon} is public and unguarded, so it must never launch a probe either
 * -- the same requirement {@link CiDaemonReadinessCheckTest} proves for the healthcheck. Each test
 * seeds a still-undecided adopted candidate straight through the repository, hits the endpoint, and
 * asserts the fake probe recorded nothing.
 */
@QuarkusTest
public class CiDaemonControllerTest {

  private static final String DAEMON = "/ci/api/daemon";

  @Inject CiDaemonPinRepository repo;
  @Inject FakeDaemonProbe probe;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(repo::deleteAll);
    probe.reset();
  }

  @Test
  public void anUnprovenCandidateIsNeverProbedByTheReadEndpoint() {
    seed("v-unproven", CiDaemonPinVerdict.UNPROVEN);

    given().when().get(DAEMON).then().statusCode(200);

    assertTrue(probe.probed().isEmpty(), probe.probed().toString());
  }

  @Test
  public void anUnknownCandidateIsNeverProbedByTheReadEndpoint() {
    seed("v-unknown", CiDaemonPinVerdict.UNKNOWN);

    given().when().get(DAEMON).then().statusCode(200);

    assertTrue(probe.probed().isEmpty(), probe.probed().toString());
  }

  private void seed(String version, CiDaemonPinVerdict verdict) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiDaemonPin row = new CiDaemonPin();
              row.id = UUID.randomUUID().toString();
              row.version = version;
              row.source = CiDaemonPins.SOURCE_ADOPTED;
              row.verdict = verdict;
              row.eventId = UUID.randomUUID().toString();
              row.occurredAt = Instant.now();
              repo.persist(row);
            });
  }
}
