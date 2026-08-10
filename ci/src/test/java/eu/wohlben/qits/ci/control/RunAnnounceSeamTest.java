package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link RunAnnouncer} seam's semantics: exactly one announcement per green run, carrying the
 * run's own coordinates, and <b>nothing</b> for a red run or a config error.
 *
 * <p>It is the whole of what a green run announces. There was a second port beside it —
 * {@code PdNotifier}, a direct POST to qits-platform-deployments' intake — and this file used to say
 * so; the deployer subscribes to {@code BuildSuccessful} durably now, so the deploy hangs off this
 * announcement and there is nothing left to be a sibling of.
 *
 * <p>The assertion worth naming is {@code finishedAt}: the wire contract
 * requires an {@code occurredAt} on every published event, so a null here would be a 400 from
 * qits-events on every single green build, discovered in a deployment rather than in a suite. It has
 * to be the timestamp on the run's own row — what the announcement says happened is the run
 * finishing, not the announcement being made.
 *
 * <p>What the production implementation does with the announcement (build a {@code BuildSuccessful},
 * hand it to the bus, land a PUT) is {@code BuildSuccessfulPublishTest}'s job in the service module.
 */
@QuarkusTest
public class RunAnnounceSeamTest extends CiTestSupport {

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo ok
      """;

  @Inject CiRunService service;
  @Inject FakeRunAnnouncer announcer;

  private String repoId;
  private String sha;

  @BeforeEach
  void resetAnnouncer() {
    announcer.reset();
  }

  private void seedConfig(String content) {
    repoId = UUID.randomUUID().toString();
    sha = UUID.randomUUID().toString().replace("-", "");
    fakeConfig.put(repoId, sha, ConfigLookup.found(content));
  }

  @Test
  public void aGreenRunIsAnnouncedOnceWithTheTimestampOnItsRow() {
    seedConfig(CONFIG_ONE_STEP);
    service.execute(repoId, "main", sha);

    CiRun run = service.runsFor(repoId).get(0);
    assertEquals(1, announcer.announced().size());
    FakeRunAnnouncer.Announced announced = announcer.announced().get(0);
    assertEquals(run.id, announced.runId());
    assertEquals(repoId, announced.repoId());
    assertEquals("main", announced.branch());
    assertEquals(sha, announced.commitSha());
    assertNotNull(announced.finishedAt(), "an event with no occurredAt is a 400 on the wire");

    // The same instant as the row's, to within the microsecond H2 ROUNDS the column to — the two
    // are not `equals` and never will be, because the announcement carries the nanosecond value
    // Instant.now() produced and the row carries what a timestamp(6) could hold. A sub-microsecond
    // gap is still the whole point of the assertion: a fresh Instant.now() taken after the
    // transaction committed would be tens of microseconds later at the very best.
    assertTrue(
        Duration.between(run.finishedAt, announced.finishedAt()).abs().toNanos() < 1_000,
        "expected the row's own finishedAt (" + run.finishedAt + "), got " + announced.finishedAt());
  }

  @Test
  public void aRedRunAnnouncesNothing() {
    seedConfig(CONFIG_ONE_STEP);
    fakeRunner.script(
        0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    service.execute(repoId, "main", sha);

    assertEquals(List.of(), announcer.announced());
  }

  @Test
  public void aConfigErrorAnnouncesNothing() {
    // A CONFIG_ERROR run never reaches the terminal transition this port hangs off — nothing built,
    // so nothing built successfully.
    seedConfig("steps: [unclosed\n");
    service.execute(repoId, "main", sha);

    assertEquals(List.of(), announcer.announced());
  }

  @Test
  public void anEmptyPipelineIsStillAGreenRunAndAnnounces() {
    // The trivially green run: config present, zero steps. It records SUCCESS, so it announces —
    // and what a subscriber does about a build that published no image is the subscriber's answer.
    seedConfig("steps: []\n");
    service.execute(repoId, "main", sha);

    assertEquals(1, announcer.announced().size());
  }
}
