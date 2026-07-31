package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The canonical form. Every assertion here is really the same one: <b>the string is a function of
 * the value</b> — because qits-events stores it verbatim and compares it byte-for-byte to tell an
 * idempotent replay (200) from a reused UUID (400). Anything that makes two serializations of one
 * event differ turns a retry into a permanent failure, which is a bug that only shows up on the
 * unhappy path.
 *
 * <p>Plain JUnit: the serializer builds its own mapper precisely so that it depends on no
 * container and no application's Jackson configuration.
 */
class CanonicalJsonTest {

  private static final Instant WHEN = Instant.parse("2026-07-31T12:46:03Z");

  /**
   * Twins: the same four fields, declared in a different order, one of them null. Field order in
   * the class must not reach the wire, so these two must serialize identically — which is what lets
   * anyone reorder a record's components without it being a wire change.
   */
  record Zeta(String zeta, String alpha, Integer mid, String absent) {}

  record Alpha(String alpha, String absent, Integer mid, String zeta) {}

  @Test
  void theSameValueSerializesToTheSameString() {
    ThingHappened event = new ThingHappened("shipped", 3, WHEN);

    assertEquals(CanonicalJson.payload(event), CanonicalJson.payload(event));
  }

  @Test
  void keysAreSortedAndTheOrderTheyWereDeclaredInDoesNotShow() {
    String zetaFirst = CanonicalJson.canonicalize(new Zeta("z", "a", 3, null));
    String alphaFirst = CanonicalJson.canonicalize(new Alpha("a", null, 3, "z"));

    assertEquals("{\"alpha\":\"a\",\"mid\":3,\"zeta\":\"z\"}", zetaFirst);
    assertEquals(zetaFirst, alphaFirst);
  }

  @Test
  void anAbsentFieldIsOmittedRatherThanWrittenAsNull() {
    String json = CanonicalJson.payload(new ThingHappened("shipped", null, WHEN));

    assertFalse(json.contains("count"), json);
    assertFalse(json.contains("null"), json);
  }

  @Test
  void thereIsNoInsignificantWhitespace() {
    String json = CanonicalJson.payload(new ThingHappened("shipped", 3, WHEN));

    assertFalse(json.contains(" "), json);
    assertFalse(json.contains("\n"), json);
  }

  @Test
  void anInstantIsIso8601RatherThanANumber() {
    String json = CanonicalJson.payload(new ThingHappened("shipped", 3, WHEN));

    assertTrue(json.contains("\"at\":\"2026-07-31T12:46:03Z\""), json);
  }

  /**
   * The four methods {@link QitsEvent} declares are envelope, not payload — and an event class is
   * allowed to hold its {@code eventId} as an ordinary component, which is what makes "generated at
   * construction, stable thereafter" expressible at all. The library is what keeps it off the wire.
   */
  @Test
  void thePayloadCarriesTheEventsOwnFieldsAndNoneOfQitsEventsMethods() {
    ThingHappened event = new ThingHappened("shipped", 3, WHEN);

    JsonNode payload = CanonicalJson.parse(CanonicalJson.payload(event));

    assertEquals(List.of("at", "count", "what"), fieldNames(payload));
    assertFalse(CanonicalJson.payload(event).contains(event.eventId().toString()));
  }

  @Test
  void aPayloadReadsBackIntoTheEventAndSerializesToTheSameStringAgain() {
    ThingHappened original = new ThingHappened("shipped", 3, WHEN);
    String payload = CanonicalJson.payload(original);

    ThingHappened restored = CanonicalJson.payloadTo(payload, ThingHappened.class);

    assertEquals("shipped", restored.what());
    assertEquals(Integer.valueOf(3), restored.count());
    assertEquals(WHEN, restored.at());
    assertEquals(payload, CanonicalJson.payload(restored));
    // The identity did NOT round-trip, and must not: it lives in the envelope, and a payload that
    // reconstructed one would be claiming an identity it never carried.
    assertNotEquals(original.eventId(), restored.eventId());
  }

  @Test
  void theEnvelopeIsTheContractsShapeWithDescriptionSpelledAsAnExplicitNull() {
    EventEnvelope envelope = EventEnvelope.of(new ThingHappened("shipped", 3, WHEN));

    String json = CanonicalJson.envelope(envelope);
    JsonNode parsed = CanonicalJson.parse(json);

    assertEquals(List.of("description", "name", "occurredAt", "payload"), fieldNames(parsed));
    assertTrue(parsed.get("description").isNull(), json);
    assertEquals("ThingHappened", parsed.get("name").asText());
    assertEquals("2026-07-31T12:46:03Z", parsed.get("occurredAt").asText());
    // payload is a STRING holding JSON, not a nested object — the server never parses it.
    assertTrue(parsed.get("payload").isTextual(), json);
    assertEquals(CanonicalJson.payload(new ThingHappened("shipped", 3, WHEN)), parsed.get("payload").asText());
  }

  @Test
  void theSubscribeFrameIsTheContractsShape() {
    assertEquals(
        "{\"subscribe\":[\"BuildSuccessful\",\"ThingHappened\"]}",
        CanonicalJson.subscribeFrame(List.of("BuildSuccessful", "ThingHappened")));
  }

  @Test
  void aFrameFromTheStreamReadsBackWhateverItCarriesAndToleratesWhatItDoesNot() {
    String text =
        """
        {"id":"%s","name":"ThingHappened","occurredAt":"2026-07-31T12:46:03Z",\
        "payload":"{\\"what\\":\\"shipped\\"}","description":null,"somethingNewer":42}
        """
            .formatted(UUID.randomUUID());

    EventFrame frame = CanonicalJson.frame(text);

    assertEquals("ThingHappened", frame.name());
    assertEquals(WHEN, frame.occurredAt());
    assertEquals("shipped", CanonicalJson.payloadTo(frame.payload(), ThingHappened.class).what());
  }

  private static List<String> fieldNames(JsonNode node) {
    return node.properties().stream().map(java.util.Map.Entry::getKey).toList();
  }
}
