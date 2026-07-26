package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for ci {@code @QuarkusTest}s: wipes both tables (steps first — FK) outside the test's own
 * transaction and resets the fakes, so every test starts from a clean slate (the {@code
 * ArtifactsTestSupport} pattern).
 */
public abstract class CiTestSupport {

  @Inject protected CiRunRepository runs;
  @Inject protected CiStepRepository steps;
  @Inject protected FakeCiStepRunner fakeRunner;
  @Inject protected FakeCiConfigSource fakeConfig;

  @BeforeEach
  void resetCiState() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.deleteAll();
              runs.deleteAll();
            });
    fakeRunner.reset();
    fakeConfig.reset();
  }
}
