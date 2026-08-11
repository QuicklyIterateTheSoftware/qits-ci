package eu.wohlben.qits.ci;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Every postgresql datasource this repository boots carries the three-line resilience block — the
 * driver that holds a connection request while postgres comes back, the validation that turns a
 * dead pooled connection into a fresh request, and the acquisition timeout that keeps the pool's
 * waiter alive while the first two work. Miss one and the other two do less than they read as; the
 * doctrine and the measurements are in the superproject's {@code
 * docs/project-setup-quinoa-angular.md} and {@code db-patience-plan.md}.
 *
 * <p><b>A {@code @QuarkusTest} rather than the plain JUnit test the library shows</b>, and it sits
 * in this module rather than in {@code service} for the same reason {@link ArchRulesTest} does: this
 * is where the datasource is declared. Two postgresql datasources boot here — ci's own and the
 * qits-eventstream outbox's, which arrives with that jar — so what has to be judged is the merged
 * configuration the application really starts with, not what a bare config could reconstruct.
 */
@QuarkusTest
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
