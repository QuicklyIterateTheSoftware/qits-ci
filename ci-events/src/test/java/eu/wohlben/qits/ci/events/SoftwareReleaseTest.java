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
 * qits-ci's second event, on the wire — the same plain-JUnit contract test {@link
 * BuildSuccessfulTest} is, for the same reason: an event class is data, and the serializer it is
 * asserted against builds its own mapper precisely so no container is needed to know what it emits.
 *
 * <p><b>The payload below is what every downstream release pipeline reads.</b> Four fields, byte for
 * byte, in the alphabetical order the canonical form fixes — a change here that is not also a change
 * in the repositories triggering on it is a cross-repo break rather than a refactor.
 */
class SoftwareReleaseTest {

  private static final Instant PUBLISHED = Instant.parse("2026-08-01T09:14:22Z");

  private static SoftwareRelease anEvent() {
    return new SoftwareRelease(
        "qits-spa-ui-components", "1.4.0", "npm", "@qits/ui-components", PUBLISHED);
  }

  @Test
  void theSignatureIsTheClassNameAndTheNameFollowsIt() {
    // The wire name IS the simple class name. qits-workspaces renames its own release event to
    // SCMRelease in the same cutover, so after it this is the only producer of this name.
    SoftwareRelease event = anEvent();

    assertEquals("SoftwareRelease", event.signature());
    assertEquals("SoftwareRelease", event.name());
  }

  @Test
  void theEventIdIsAV4GeneratedOnceAndStableThereafter() {
    SoftwareRelease event = anEvent();

    UUID first = event.eventId();
    assertEquals(4, first.version(), "the idempotency key must be random, not derived");
    assertSame(first, event.eventId());
    // Two artifacts of one run are two occurrences and must not collide on one id.
    assertNotEquals(first, anEvent().eventId());
  }

  @Test
  void theEnvelopeCarriesTheFourFieldsAndNothingElse() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("SoftwareRelease", json.get("name").asText());
    assertEquals("2026-08-01T09:14:22Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"packageName\":\"@qits/ui-components\",\"packageType\":\"npm\","
            + "\"repository\":\"qits-spa-ui-components\",\"version\":\"1.4.0\"}",
        json.get("payload").asText());
  }

  @Test
  void aDockerArtifactCarriesAnUnqualifiedName() {
    // No registry-qualified reference is portable: the registry is qits-artifacts:8080 inside a step
    // container and registry.dev.localhost:8080 to qits-ci and qits-cd, and an OCI reference
    // cannot carry a path prefix. The consumer qualifies it with the address that is right where
    // it stands.
    SoftwareRelease image =
        new SoftwareRelease("qits-stt", "2026.8.1", "docker", "qits/qits-stt", PUBLISHED);

    assertEquals(
        "{\"packageName\":\"qits/qits-stt\",\"packageType\":\"docker\","
            + "\"repository\":\"qits-stt\",\"version\":\"2026.8.1\"}",
        CanonicalJson.payload(image));
  }

  @Test
  void theIdentityAndTheTimestampTravelInTheEnvelopeAndNeverInThePayload() {
    // occurredAt is an ordinary record component here rather than an override of a differently named
    // field, and it still stays out: CanonicalJson excludes everything QitsEvent declares, so no
    // @JsonIgnore is spelled on the event class.
    SoftwareRelease event = anEvent();

    String payload = CanonicalJson.payload(event);

    assertFalse(payload.contains("eventId"), payload);
    assertFalse(payload.contains(event.eventId().toString()), payload);
    assertFalse(payload.contains("occurredAt"), payload);
    assertFalse(payload.contains("signature"), payload);
  }

  @Test
  void aSubscriberReadsThePayloadBackIntoTheEvent() {
    SoftwareRelease published = anEvent();

    SoftwareRelease received =
        CanonicalJson.payloadTo(CanonicalJson.payload(published), SoftwareRelease.class);

    assertEquals(published.repository(), received.repository());
    assertEquals(published.version(), received.version());
    assertEquals(published.packageType(), received.packageType());
    assertEquals(published.packageName(), received.packageName());
  }
}
