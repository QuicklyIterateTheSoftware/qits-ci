package eu.wohlben.qits.ci.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The failure twin of {@link BuildSuccessfulTest}, and the same kind of contract: these assertions
 * are what a subscriber keeping per-commit build status binds against, so a change here that is not
 * also a change there is a cross-repo break rather than a refactor.
 */
class BuildFailedTest {

  private static final Instant FINISHED = Instant.parse("2026-07-31T12:46:03Z");

  private static BuildFailed anEvent() {
    return new BuildFailed(
        "run-1", "repo-uuid", "qits", "qits-ci", "main", "0123456789abcdef", "FAILED", FINISHED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    BuildFailed event = anEvent();

    assertEquals("BuildFailed", event.signature());
    assertEquals("BuildFailed", event.name());
  }

  @Test
  void occurredAtIsWhenTheBuildFinishedRatherThanWhenItWasAnnounced() {
    assertEquals(FINISHED, anEvent().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    BuildFailed event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("BuildFailed", json.get("name").asText());
    assertEquals("2026-07-31T12:46:03Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"FAILED\","
            + "\"projectId\":\"qits\",\"repoId\":\"repo-uuid\",\"repoName\":\"qits-ci\","
            + "\"runId\":\"run-1\"}",
        json.get("payload").asText());
    assertEquals(true, json.get("description").isNull(), "description is an explicit null");
    assertEquals(true, json.get("parentId").isNull(), "a build nothing caused is a chain root");
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    BuildFailed event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void anIdAddressedPushOmitsTheNamePairRatherThanNullingIt() {
    BuildFailed idOnly =
        new BuildFailed(
            "run-3", "qits-ci", null, null, "main", "0123456789abcdef", "TIMED_OUT", FINISHED);

    String payload = CanonicalJson.payload(idOnly);

    assertFalse(payload.contains("projectId"), payload);
    assertFalse(payload.contains("repoName"), payload);
    assertFalse(payload.contains("null"), payload);
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"outcome\":\"TIMED_OUT\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-3\"}",
        payload);
  }

  @Test
  void aSubscriberReadsThePayloadBackIntoTheEvent() {
    BuildFailed published = anEvent();

    BuildFailed received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), BuildFailed.class);

    assertEquals(published.runId(), received.runId());
    assertEquals(published.repoId(), received.repoId());
    assertEquals(published.projectId(), received.projectId());
    assertEquals(published.repoName(), received.repoName());
    assertEquals(published.branch(), received.branch());
    assertEquals(published.commitSha(), received.commitSha());
    assertEquals(published.outcome(), received.outcome());
    assertEquals(published.finishedAt(), received.finishedAt());
  }
}
