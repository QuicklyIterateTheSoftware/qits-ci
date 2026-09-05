package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link RunAnnouncer} seam's semantics: exactly one announcement per finished run that says
 * something true about its commit — the green kind for a green run, the failure kind (with the
 * terminal status's own word) for a red run or a config error — and <b>nothing</b> for a cancelled
 * one.
 *
 * <p>It is the whole of what a green run announces. There was a second port beside it —
 * {@code PdNotifier}, a direct POST to qits-platform-deployments' intake — and this file used to say
 * so; the deployer subscribes off the bus now (to {@code SoftwareRelease}, since a green build is no
 * longer a reason to deploy), so there is nothing left to be a sibling of. What hangs off this
 * announcement is qits-projects' release-request gate.
 *
 * <p>The assertion worth naming is {@code finishedAt}: the wire contract
 * requires an {@code occurredAt} on every published event, so a null here would be a 400 from
 * qits-events on every single green build, discovered in a deployment rather than in a suite. It has
 * to be the timestamp on the run's own row — what the announcement says happened is the run
 * finishing, not the announcement being made.
 *
 * <p>What the production implementation does with the announcement (build a {@code BuildSuccessful}
 * or {@code BuildFailed}, hand it to the bus, land a PUT) is {@code BuildSuccessfulPublishTest}'s
 * job in the service module.
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

  private String pipeline;

  private void seedConfig(String content) {
    repoId = UUID.randomUUID().toString();
    sha = UUID.randomUUID().toString().replace("-", "");
    pipeline = content;
  }

  private void run() {
    executePipeline(repoId, "main", sha, pipeline);
  }

  @Test
  public void aGreenRunIsAnnouncedOnceWithTheTimestampOnItsRow() {
    seedConfig(CONFIG_ONE_STEP);
    run();

    CiRun run = service.runsFor(repoId).get(0);
    assertEquals(1, announcer.announced().size());
    assertEquals(List.of(), announcer.failed(), "green and red are exclusive verdicts");
    FakeRunAnnouncer.Announced announced = announcer.announced().get(0);
    assertEquals(run.id, announced.runId());
    assertEquals(repoId, announced.repoId());
    assertEquals("main", announced.branch());
    assertEquals(sha, announced.commitSha());
    assertTrue(announced.gating(), "a file that declares nothing is gating");
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
  public void aRedRunAnnouncesItsFailureAndNeverTheGreenEvent() {
    seedConfig(CONFIG_ONE_STEP);
    fakeRunner.script(
        0, new CiStepRunner.StepResult(1, false, CiStepRunner.StepOutcome.OK, "boom"));
    run();

    CiRun run = service.runsFor(repoId).get(0);
    assertEquals(List.of(), announcer.announced());
    assertEquals(1, announcer.failed().size());
    FakeRunAnnouncer.AnnouncedFailure failure = announcer.failed().get(0);
    assertEquals(run.id, failure.runId());
    assertEquals(repoId, failure.repoId());
    assertEquals("main", failure.branch());
    assertEquals(sha, failure.commitSha());
    assertEquals("FAILED", failure.outcome());
    assertNotNull(failure.finishedAt(), "an event with no occurredAt is a 400 on the wire");
  }

  // There used to be a third case here, `aConfigErrorAnnouncesItsFailureWithItsOwnWord`: a pipeline
  // that could not be parsed was a CONFIG_ERROR row and a red announcement. It went with the push
  // arm on 2026-09-05 — a trigger file is read and parsed by CiEventTriggerService BEFORE a row
  // exists, so an unparseable one is a WARN and no run at all, and nothing writes CONFIG_ERROR any
  // more. What a broken trigger file does is CiEventTriggerServiceTest's.

  @Test
  public void anEmptyPipelineIsStillAGreenRunAndAnnounces() {
    // The trivially green run: config present, zero steps. It records SUCCESS, so it announces —
    // and what a subscriber does about a build that published no image is the subscriber's answer.
    seedConfig("steps: []\n");
    run();

    assertEquals(1, announcer.announced().size());
  }
}
