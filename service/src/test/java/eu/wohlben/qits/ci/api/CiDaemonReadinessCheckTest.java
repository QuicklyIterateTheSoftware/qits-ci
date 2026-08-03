package eu.wohlben.qits.ci.api;

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
import org.eclipse.microprofile.health.Readiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fix 3: the container healthcheck hits this class every few seconds, so it must never launch a
 * probe. Each test seeds a still-undecided adopted candidate straight through the repository --
 * bypassing {@code pins.adopt()} + a probe entirely -- then calls {@link CiDaemonReadinessCheck#call}
 * and asserts the fake probe recorded nothing.
 */
@QuarkusTest
public class CiDaemonReadinessCheckTest {

  @Inject @Readiness CiDaemonReadinessCheck check;
  @Inject CiDaemonPinRepository repo;
  @Inject FakeDaemonProbe probe;

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew().run(repo::deleteAll);
    probe.reset();
  }

  @Test
  public void anUnprovenCandidateIsNeverProbedByTheReadinessCheck() {
    seed("v-unproven", CiDaemonPinVerdict.UNPROVEN);

    check.call();

    assertTrue(probe.probed().isEmpty(), probe.probed().toString());
  }

  @Test
  public void anUnknownCandidateIsNeverProbedByTheReadinessCheck() {
    seed("v-unknown", CiDaemonPinVerdict.UNKNOWN);

    check.call();

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
