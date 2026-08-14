package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for ci {@code @QuarkusTest}s: wipes the tables (steps first — FK) outside the test's own
 * transaction and resets the fakes, so every test starts from a clean slate (the {@code
 * ArtifactsTestSupport} pattern).
 *
 * <p>The release join's two tables are wiped here too, and the reason is the same one that makes the
 * trigger engine's shared candidate list bite: they outlive a run row on purpose, so a release fact
 * one test recorded would close another test's join and turn "held, nothing announced" into an
 * announcement nobody asked for.
 */
public abstract class CiTestSupport {

  @Inject protected CiRunRepository runs;
  @Inject protected CiStepRepository steps;
  @Inject protected CiReleaseAnnouncementRepository announcements;
  @Inject protected CiScmReleaseRepository scmReleases;
  @Inject protected FakeCiStepRunner fakeRunner;
  @Inject protected FakeCiConfigSource fakeConfig;
  @Inject protected FakeCandidateRepos fakeCandidates;
  @Inject protected CiRunService runService;

  /**
   * Drop everything this test thread has already loaded, so the next read really goes to the
   * database.
   *
   * <p>Needed exactly when a test reads a row <em>before</em> the run worker changes it: a {@code
   * @QuarkusTest} method has one request-scoped persistence context, and Hibernate's identity map
   * wins over a query's own results — so a second read would hand back the stale instance the first
   * read cached and the test would be asserting against its own memory rather than the worker's
   * work.
   */
  protected void forgetLoadedEntities() {
    runs.getEntityManager().clear();
  }

  @BeforeEach
  void resetCiState() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.deleteAll();
              runs.deleteAll();
              announcements.deleteAll();
              scmReleases.deleteAll();
            });
    fakeRunner.reset();
    fakeConfig.reset();
    // No patience by default: a test that stages an unreachable git host asserts the decision, not
    // the shipped five minutes of sitting out a redeploy. The retry test arms its own schedule.
    runService.unreachableRetryDelays(List.of());
    // Empty by default, so no suite evaluates a trigger it did not ask for — the same reason the
    // eventstream module's recording raw listeners want nothing until a test arms them.
    fakeCandidates.reset();
  }
}
