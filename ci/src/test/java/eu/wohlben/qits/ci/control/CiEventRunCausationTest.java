package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The row a bus-arrived event leaves carries its cause in BOTH columns, and the generic one is set
 * as data, not by the entity listener. The engine evaluates behind a queue hop, so the ambient
 * {@code CausationScope} is gone by the time the row is persisted — this suite drives {@code
 * evaluate} from a thread with no scope at all, which is exactly that condition: a green assertion
 * here means {@code acceptEventRun} set the value itself. Measured before the fix: live event runs
 * with a full {@code trigger_event_id} beside an empty {@code causation_id}.
 */
@QuarkusTest
public class CiEventRunCausationTest extends CiTestSupport {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-upstream.yml";

  private static final String TRIGGER =
      """
      event: BuildSuccessful
      steps:
        - image: alpine:3
          script: echo bump
      """;

  private static final String HEAD = "f".repeat(40);

  @Inject CiEventTriggerService engine;
  @Inject CiRunService runService;

  private String repoId;

  @BeforeEach
  void seed() {
    repoId = "caused-" + UUID.randomUUID().toString().substring(0, 8);
    fakeCandidates.set(repoId);
    fakeConfig.putTriggers(repoId, "main", HEAD, new EventTriggerFile(TRIGGER_PATH, TRIGGER));
  }

  private void deliver(String eventId) throws Exception {
    engine.evaluate(
        new CiEventTriggerService.Arrival(
            eventId, "BuildSuccessful", Instant.now(), "{\"repoId\":\"upstream\"}"));
    runService.awaitIdle();
    forgetLoadedEntities();
  }

  @Test
  public void anEventRunCarriesItsCauseInBothColumns() throws Exception {
    String eventId = UUID.randomUUID().toString();

    deliver(eventId);

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals(eventId, run.triggerEventId);
    assertEquals(
        UUID.fromString(eventId),
        run.causationId,
        "no scope stands on this thread, so only an explicit set can have written this");
  }

  /** The same leniency the dispatcher gives a non-UUID frame id: the run runs, the edge is lost. */
  @Test
  public void anEventIdThatIsNotAUuidCostsTheEdgeAndNothingElse() throws Exception {
    deliver("not-a-uuid");

    CiRun run = runService.runsFor(repoId).get(0);
    assertEquals("not-a-uuid", run.triggerEventId);
    assertNull(run.causationId);
  }
}
