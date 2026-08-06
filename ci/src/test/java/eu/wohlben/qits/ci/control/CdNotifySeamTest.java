package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link CdNotifier} seam's semantics, driven synchronously through the orchestrator: exactly
 * one announcement per green run, carrying the run's own coordinates, and <b>nothing</b> for a red
 * run or a config error — a deployment must only ever follow a build that actually passed. What
 * the production implementation does with the announcement (the HTTP POST, the token header) is
 * {@code CdBuildNotifierTest}'s job in the service module.
 */
@QuarkusTest
public class CdNotifySeamTest extends CiTestSupport {

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo ok
      """;

  @Inject CiRunService service;
  @Inject FakeCdNotifier cdNotifier;

  private String repoId;
  private String sha;

  @BeforeEach
  void resetNotifier() {
    cdNotifier.reset();
  }

  private void seedConfig(String content) {
    repoId = UUID.randomUUID().toString();
    sha = UUID.randomUUID().toString().replace("-", "");
    fakeConfig.put(repoId, sha, ConfigLookup.found(content));
  }

  @Test
  public void aGreenRunIsAnnouncedOnceWithItsCoordinates() {
    seedConfig(CONFIG_ONE_STEP);
    service.execute(repoId, "epic/some-epic", sha);

    CiRun run = service.runsFor(repoId).get(0);
    assertEquals(
        List.of(new FakeCdNotifier.Notified(run.id, repoId, "epic/some-epic", sha)),
        cdNotifier.notified());
  }

  @Test
  public void aRedRunAnnouncesNothing() {
    seedConfig(CONFIG_ONE_STEP);
    fakeRunner.script(
        0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    service.execute(repoId, "main", sha);

    assertEquals(List.of(), cdNotifier.notified());
  }

  @Test
  public void aConfigErrorAnnouncesNothing() {
    seedConfig("steps: [unclosed\n");
    service.execute(repoId, "main", sha);

    assertEquals(List.of(), cdNotifier.notified());
  }

  @Test
  public void anEmptyPipelineIsStillAGreenRunAndAnnounces() {
    // The trivially green run: config present, zero steps. It records SUCCESS, so it announces —
    // the deployer's IMAGE_MISSING is the honest outcome if nothing published an image for it.
    seedConfig("steps: []\n");
    service.execute(repoId, "main", sha);

    assertEquals(1, cdNotifier.notified().size());
  }
}
