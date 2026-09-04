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
 * <p><b>The payload below is what every downstream release pipeline reads.</b> Seven fields now, byte
 * for byte, in the alphabetical order the canonical form fixes — a change here that is not also a
 * change in the repositories triggering on it is a cross-repo break rather than a refactor.
 *
 * <p><b>{@code projectId}, {@code repoId} and {@code repoName} were ADDED to the original four and
 * nothing was repointed</b>, which is what the byte assertions below are for: {@code repository}
 * still carries what it always carried, so a consumer selecting on it is unaffected, and the new keys
 * sit beside it for a consumer that has to address the repository rather than recognise it.
 * {@code repoName} is the one the deployer cannot work without — a released repository's
 * deployments.yml is read at {@code /git/<projectId>/<repoName>/blob/…}, and the id-addressed
 * fallback is refused to everyone but qits-projects. The null cases have their own tests, because
 * "absent" is the shipped answer for a repository the candidate listing knows only by id and a
 * consumer must read it as "ask somebody" rather than as an address.
 */
class SoftwareReleaseTest {

  private static final Instant PUBLISHED = Instant.parse("2026-08-01T09:14:22Z");

  private static SoftwareRelease anEvent() {
    return new SoftwareRelease(
        "qits-spa-ui-components",
        "p-1",
        "qits-spa-ui-components",
        "qits-spa-ui-components",
        "1.4.0",
        "npm",
        "@qits/ui-components",
        PUBLISHED);
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
  void theEnvelopeCarriesTheSixFieldsAndNothingElse() {
    EventEnvelope envelope = EventEnvelope.of(anEvent());
    JsonNode json = CanonicalJson.parse(CanonicalJson.envelope(envelope));

    assertEquals(
        List.of("description", "environment", "name", "occurredAt", "parentId", "payload"),
        json.properties().stream().map(Map.Entry::getKey).toList());
    assertEquals("SoftwareRelease", json.get("name").asText());
    assertEquals("2026-08-01T09:14:22Z", json.get("occurredAt").asText());
    assertEquals(
        "{\"packageName\":\"@qits/ui-components\",\"packageType\":\"npm\","
            + "\"projectId\":\"p-1\",\"repoId\":\"qits-spa-ui-components\","
            + "\"repoName\":\"qits-spa-ui-components\","
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
        new SoftwareRelease(
            "3f1c-uuid",
            "p-7",
            "3f1c-uuid",
            "qits-stt-service",
            "2026.8.1",
            "docker",
            "qits/qits-stt",
            PUBLISHED);

    assertEquals(
        "{\"packageName\":\"qits/qits-stt\",\"packageType\":\"docker\",\"projectId\":\"p-7\","
            + "\"repoId\":\"3f1c-uuid\",\"repoName\":\"qits-stt-service\","
            + "\"repository\":\"3f1c-uuid\",\"version\":\"2026.8.1\"}",
        CanonicalJson.payload(image));
  }

  /**
   * The repository is addressable, and the two new fields are what make it so.
   *
   * <p>{@code repository} is unchanged and unrepointed — it is the id it always was — and {@code
   * repoId} is the same string under the name the platform uses for it, which is the whole of why
   * adding it costs no consumer anything. {@code projectId} and {@code repoName} are the facts
   * nothing on this event could previously supply, and the reason a deploy consumer no longer has to
   * ask qits-projects on the dispatch thread for something the publisher already held — {@code
   * repoName} in particular, because the name-addressed read is the only one it is allowed to make.
   */
  @Test
  void theRepositoryIsAddressableWithoutALookup() {
    SoftwareRelease event = anEvent();

    assertEquals("p-1", event.projectId());
    assertEquals("qits-spa-ui-components", event.repoId());
    assertEquals("qits-spa-ui-components", event.repoName());
    assertEquals(event.repository(), event.repoId(), "the same value, under both names");
  }

  /**
   * A run whose candidate repository was answered id-addressed carries neither project nor name, and
   * both keys are then <b>absent</b> rather than null.
   *
   * <p>That is {@code CanonicalJson}'s NON_NULL inclusion doing what the wire rules say, and it is
   * asserted here because it is the shape a consumer actually has to handle: "the key is not there"
   * is the only honest spelling of "qits-ci does not know", and a null would have been a value. A
   * deploy consumer reading this one has no name-addressed read available to it and must say so,
   * rather than building an id-addressed url the git host will refuse it.
   */
  @Test
  void aRunWithNoPublicCoordinatePublishesNeitherKeyAtAll() {
    SoftwareRelease unaddressed =
        new SoftwareRelease(
            "legacy-repo",
            null,
            "legacy-repo",
            null,
            "1.4.0",
            "npm",
            "@qits/ui-components",
            PUBLISHED);

    assertEquals(
        "{\"packageName\":\"@qits/ui-components\",\"packageType\":\"npm\","
            + "\"repoId\":\"legacy-repo\",\"repository\":\"legacy-repo\",\"version\":\"1.4.0\"}",
        CanonicalJson.payload(unaddressed));
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
    assertEquals(published.projectId(), received.projectId());
    assertEquals(published.repoId(), received.repoId());
    assertEquals(published.repoName(), received.repoName());
    assertEquals(published.version(), received.version());
    assertEquals(published.packageType(), received.packageType());
    assertEquals(published.packageName(), received.packageName());
  }
}
