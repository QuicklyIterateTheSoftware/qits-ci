package eu.wohlben.qits.eventsourcing.control;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The one place JSON is produced or read on the way to and from qits-events.
 *
 * <p><b>Canonical means the string is a function of the value and of nothing else.</b> Object keys
 * are sorted, there is no insignificant whitespace, and an absent (null) field is omitted rather
 * than written as an explicit null. That matters because canonicalization happens <em>here</em>,
 * in the publisher, while the server stores the string verbatim and compares it byte-for-byte to
 * decide whether a repeated {@code PUT} is an idempotent replay (200) or a reused UUID (400). Two
 * serializations of the same event that differ by a space or by field order are, to qits-events, a
 * contradiction — so every knob that could make them differ is set explicitly below rather than
 * inherited.
 *
 * <p>Which is also why this mapper is built by hand instead of injecting the CDI {@code
 * ObjectMapper}: that one belongs to the consuming application and any {@code
 * ObjectMapperCustomizer} anywhere on its classpath can change it. The wire contract cannot be
 * downstream of that.
 *
 * <p>Three settings carry the whole guarantee and none of them is a default:
 *
 * <ul>
 *   <li>{@code SORT_PROPERTIES_ALPHABETICALLY} — key order stops depending on the order fields
 *       happen to be declared in, so reordering a record's components is not a wire change.
 *   <li>{@code SORT_CREATOR_PROPERTIES_FIRST} <b>off</b> — on (its default) creator properties are
 *       emitted in declaration order ahead of the rest, which is exactly the dependency the line
 *       above removes, and every event class is creator-based. Jackson 2.21 happens to let
 *       alphabetical win anyway; the explicit disable is what keeps that from being a version fact.
 *   <li>{@code NON_NULL} inclusion — "no nulls for absent fields". The envelope is the one exception
 *       and says so on itself with {@code @JsonInclude(ALWAYS)}.
 * </ul>
 *
 * <p>{@code WRITE_DATES_AS_TIMESTAMPS} is disabled so an {@link Instant} is
 * {@code 2026-07-31T12:46:03Z} rather than a float — the contract's spelling, and the one a human
 * reading the event log can compare.
 *
 * <p>The mix-in is what keeps {@link QitsEvent}'s own four methods out of a payload. An event class
 * is free to hold its {@code eventId} as an ordinary record component — the accessor matches the
 * interface method, so the mix-in hides it — and the library, not each event author, is what
 * guarantees identity travels in the envelope and never in the body.
 */
public final class CanonicalJson {

  /** Hides {@link QitsEvent}'s declared methods from an event's payload. See the class javadoc. */
  private abstract static class QitsEventMixin {

    @JsonIgnore
    abstract String signature();

    @JsonIgnore
    abstract UUID eventId();

    @JsonIgnore
    abstract Instant occurredAt();

    @JsonIgnore
    abstract String name();
  }

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
          // Maps are not sorted by the property setting above — they carry their own iteration
          // order — and an event holding one would otherwise serialize differently per insertion
          // order.
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
          // A frame from a newer qits-events must not kill this subscriber.
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .addMixIn(QitsEvent.class, QitsEventMixin.class)
          .build();

  private CanonicalJson() {}

  /**
   * An event's fields as canonical JSON — the envelope's {@code payload} string. Everything {@link
   * QitsEvent} declares is excluded; what is left is the implementation's own data.
   */
  public static String payload(QitsEvent event) {
    return canonicalize(event);
  }

  /**
   * Any value in canonical form. {@link #payload(QitsEvent)} is the entry point that matters; this
   * is the same serializer without the event-shaped name, which is what the determinism tests drive
   * so that the guarantee is asserted about the serializer rather than about one event class.
   */
  public static String canonicalize(Object value) {
    return write(value);
  }

  /** The envelope, as the {@code PUT} body. Not canonical by contract, but produced by the same mapper. */
  public static String envelope(EventEnvelope envelope) {
    return write(envelope);
  }

  /**
   * The one frame this module sends: {@code {"subscribe":["A","B"]}}, which replaces the
   * connection's subscription set. The list is passed through in the order given — the dispatcher
   * sorts it — so the frame is stable across boots and a test can assert on it literally.
   */
  public static String subscribeFrame(List<String> signatures) {
    return write(Map.of("subscribe", List.copyOf(signatures)));
  }

  /** Reads a payload string back into the event class a listener asked for. */
  public static <E> E payloadTo(String payload, Class<E> type) {
    try {
      return MAPPER.readValue(payload == null ? "{}" : payload, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("payload is not a " + type.getSimpleName() + ": " + e, e);
    }
  }

  /** Reads one frame off {@code /events/stream}. */
  public static EventFrame frame(String text) {
    try {
      return MAPPER.readValue(text, EventFrame.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("not an event frame: " + e, e);
    }
  }

  /** Parses arbitrary JSON — the tests' way of asserting on shape without hand-rolling a parser. */
  public static JsonNode parse(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("not JSON: " + e, e);
    }
  }

  private static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      // Unserializable event data is a programming error in the event class, not a runtime
      // condition to retry — and it must surface at the publish call rather than as an outbox row
      // nobody can explain.
      throw new IllegalArgumentException(
          "cannot serialize " + value.getClass().getName() + ": " + e, e);
    }
  }
}
