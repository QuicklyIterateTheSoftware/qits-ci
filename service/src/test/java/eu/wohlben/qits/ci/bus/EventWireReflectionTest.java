package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.ci.events.SoftwareRelease;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection} — which is to say, guards the <em>completeness</em> of the
 * registration, because its correctness is not something this suite can reach.
 *
 * <p><b>Say plainly what a JVM test can and cannot prove here.</b> On a JVM every class reflects
 * whether anyone registered it or not, so nothing below would have failed on the build that shipped
 * the binary where publishing was dead — and nothing below would fail again if the annotation were
 * deleted tomorrow, except the assertions that read the annotation itself. Only the <b>native
 * artifact</b>, running, proves that the registration does its job; on this platform that proof is
 * the round trip through a real qits-events (a green build's {@code BuildSuccessful} arriving back
 * on {@code /events/stream} and being logged by {@link BuildSuccessfulListener}). What is written
 * here is the part that <em>is</em> checkable: that the set of registered types still covers every
 * type the wire path touches, and that the one entry named as a string still resolves. A test
 * pretending to more than that — "native reflection works" asserted in surefire — would pass
 * vacuously and be worse than none, which is the trap this file exists inside rather than above.
 * The repo's other native-only traps are documented the same way; see {@code AGENTS.md}.
 */
@QuarkusTest
public class EventWireReflectionTest {

  /** The private nested mix-in {@link EventWireReflection} can only name as a string. */
  private static final String MIXIN = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin";

  @Inject @Any Instance<QitsDurableEventListener> listeners;

  @Test
  public void theRegisteredTargetsAreExactlyTheTypesThatCrossTheWire() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    assertEquals(
        Set.of(
            BuildSuccessful.class,
            SoftwareRelease.class,
            SCMPublishCommit.class,
            EventEnvelope.class,
            EventFrame.class),
        Set.of(registration.targets()),
        "the two events out, the push in, the PUT body, the frame — a sixth wire type is added here");
  }

  /**
   * The rule that generalises: a listener bean is how this service declares it wants an event, and
   * an unregistered one is a binary that subscribes to a signature it cannot deserialize. That
   * failure is now at least audible ({@code EventDispatcher} warns on an unreadable frame) but it is
   * still a defect, and this is the line that catches it at build time instead.
   *
   * <p><b>Written against signatures rather than classes, because the durable seam has no {@code
   * eventType()}.</b> All three listeners here take an {@code EventFrame} and deserialize what they
   * want themselves, so the class each one binds to is not something a test can ask the bean for.
   * What it can ask is the name each one subscribes to, and the registration's own targets carry
   * those names: a signature is an event class's simple name, by the same derivation the typed seam
   * used. {@code "*"} is skipped — a listener that wants everything is promising to bind nothing in
   * particular, and the trigger engine really does read its payloads with {@code readTree}.
   */
  @Test
  public void everyDurableListenersSignatureNamesARegisteredType() {
    Set<String> registered =
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).targets())
            .stream()
            .map(Class::getSimpleName)
            .collect(Collectors.toSet());
    for (QitsDurableEventListener listener : listeners) {
      for (String signature : listener.signatures()) {
        if (QitsRawEventListener.ALL.equals(signature)) {
          continue;
        }
        assertTrue(
            registered.contains(signature),
            listener.getClass().getName()
                + " listens for "
                + signature
                + ", which no registered type is named after");
      }
    }
  }

  /**
   * The string entry, kept honest. A rename or a move of the mix-in would otherwise leave a
   * registration that names nothing, silently — and its consequence is not a crash but {@code
   * eventId} appearing in the canonical payload, which is a wire contract violation qits-events
   * answers with a 400 the next time the same id is replayed.
   */
  @Test
  public void theMixinNamedByStringStillExistsAndStillHidesTheEnvelopesFields() throws Exception {
    Class<?> mixin = Class.forName(MIXIN);
    assertEquals(
        MIXIN,
        Set.of(EventWireReflection.class.getAnnotation(RegisterForReflection.class).classNames())
            .iterator()
            .next());
    Method eventId = mixin.getDeclaredMethod("eventId");
    assertNotNull(
        eventId.getAnnotation(JsonIgnore.class),
        "the mix-in is registered because this @JsonIgnore is read by reflection");
  }

  /**
   * The three types in one flow, which is the flow the binary died in: an event is canonicalized
   * into an envelope, the envelope is written as the PUT body, and a frame carrying that same
   * payload is read back and bound to the event class a listener asked for. Every step of it is a
   * Jackson bind of a type nothing else in this application hands to the CDI {@code ObjectMapper}.
   */
  @Test
  public void theWholeWirePathBindsBothWays() {
    Instant finishedAt = Instant.parse("2026-07-31T12:46:03Z");
    BuildSuccessful out =
        new BuildSuccessful("run-1", "qits-ci", "main", "0123456789abcdef", null, finishedAt);

    EventEnvelope envelope = EventEnvelope.of(out);
    JsonNode body = CanonicalJson.parse(CanonicalJson.envelope(envelope));
    assertEquals("BuildSuccessful", body.get("name").asText());
    assertTrue(body.get("payload").isTextual(), "payload is a string the server stores verbatim");

    EventFrame frame =
        CanonicalJson.frame(
            CanonicalJson.canonicalize(
                new EventFrame(
                    UUID.randomUUID().toString(),
                    envelope.name(),
                    envelope.occurredAt(),
                    envelope.payload(),
                    null,
                    envelope.parentId())));
    BuildSuccessful back = CanonicalJson.payloadTo(frame.payload(), BuildSuccessful.class);

    assertEquals(out.runId(), back.runId());
    assertEquals(out.repoId(), back.repoId());
    assertEquals(out.branch(), back.branch());
    assertEquals(out.commitSha(), back.commitSha());
    assertEquals(out.finishedAt(), back.finishedAt());
    // Identity is the envelope's, so the rebuilt instance gets a fresh one — see BuildSuccessful.
    assertFalse(out.eventId().equals(back.eventId()));
  }
}
