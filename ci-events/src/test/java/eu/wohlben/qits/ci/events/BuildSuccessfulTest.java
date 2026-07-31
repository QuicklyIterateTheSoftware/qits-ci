package eu.wohlben.qits.ci.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventsourcing.control.CanonicalJson;
import eu.wohlben.qits.eventsourcing.control.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * qits-ci's one event, on the wire. Plain JUnit — an event class is data, and the serializer it is
 * asserted against builds its own mapper precisely so no container is needed to know what it emits.
 *
 * <p>These assertions are the contract the qits-events side was written against, so a change here
 * that is not also a change there is a cross-repo break rather than a refactor.
 */
class BuildSuccessfulTest {

  private static final Instant FINISHED = Instant.parse("2026-07-31T12:46:03Z");

  private static BuildSuccessful anEvent() {
    return new BuildSuccessful(
        "run-1", "qits-ci", "main", "0123456789abcdef", "sha256:deadbeef", FINISHED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    BuildSuccessful event = anEvent();

    assertEquals("BuildSuccessful", event.signature());
    assertEquals("BuildSuccessful", event.name());
  }

  @Test
  void occurredAtIsWhenTheBuildFinishedRatherThanWhenItWasAnnounced() {
    assertEquals(FINISHED, anEvent().occurredAt());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    BuildSuccessful event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two announcements of the same facts are two occurrences and must not collide on one id.
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeIsThePlansShape() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("BuildSuccessful", json.get("name").asText());
    assertEquals("2026-07-31T12:46:03Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"branch\":\"main\",\"commitSha\":\"0123456789abcdef\","
            + "\"finishedAt\":\"2026-07-31T12:46:03Z\",\"imageDigest\":\"sha256:deadbeef\","
            + "\"repoId\":\"qits-ci\",\"runId\":\"run-1\"}",
        json.get("payload").asText());
    assertEquals(true, json.get("description").isNull(), "description is an explicit null");
    // parentId joined the envelope when causation landed. BuildSuccessful itself did NOT change —
    // that is the design: no event class ever declares a parent, so the payload above is the same
    // six keys it always was, and the causation lives one level out where the server compares it.
    assertEquals(true, json.get("parentId").isNull(), "a build nothing caused is a chain root");
  }

  @Test
  void theIdentityTravelsInTheEnvelopeAndNeverInThePayload() {
    BuildSuccessful event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aPipelineThatPublishedNoImageOmitsTheDigestRatherThanNullingIt() {
    BuildSuccessful noImage =
        new BuildSuccessful("run-2", "qits-ci", "main", "0123456789abcdef", null, FINISHED);

    String payload = CanonicalJson.payload(noImage);

    assertFalse(payload.contains("imageDigest"), payload);
    assertFalse(payload.contains("null"), payload);
  }

  @Test
  void aSubscriberReadsThePayloadBackIntoTheEvent() {
    BuildSuccessful published = anEvent();

    BuildSuccessful received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), BuildSuccessful.class);

    assertEquals(published.runId(), received.runId());
    assertEquals(published.repoId(), received.repoId());
    assertEquals(published.branch(), received.branch());
    assertEquals(published.commitSha(), received.commitSha());
    assertEquals(published.imageDigest(), received.imageDigest());
    assertEquals(published.finishedAt(), received.finishedAt());
  }
}
