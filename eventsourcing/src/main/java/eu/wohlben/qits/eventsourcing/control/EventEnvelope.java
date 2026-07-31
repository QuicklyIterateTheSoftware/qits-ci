package eu.wohlben.qits.eventsourcing.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import java.time.Instant;

/**
 * The body of {@code PUT /events/api/events/{id}} — and, minus the id qits-events adds, the same
 * shape that comes back out of {@code /events/stream}. The frozen wire contract:
 *
 * <pre>{@code
 * {"name":"BuildSuccessful","occurredAt":"2026-07-31T12:46:03Z","payload":"{…}","description":null}
 * }</pre>
 *
 * <p>{@code name} doubles as the signature; {@code payload} is the event's fields as a canonical
 * JSON <em>string</em> (a string, not a nested object — the server stores and compares it verbatim
 * and never has to parse it); {@code description} is the human account, which an event published
 * this way does not have.
 *
 * <p>The {@code @JsonInclude(ALWAYS)} is the one deliberate exception to {@link CanonicalJson}'s
 * omit-nulls rule and belongs on this type rather than in the mapper: the contract spells {@code
 * description} as an explicit null, and an envelope that quietly dropped the key would be a
 * different document than the one both sides were written against. Nothing else here is ever null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EventEnvelope(String name, Instant occurredAt, String payload, String description) {

  /** The envelope for an event: its name, its time, its canonicalized fields, no description. */
  public static EventEnvelope of(QitsEvent event) {
    return new EventEnvelope(event.name(), event.occurredAt(), CanonicalJson.payload(event), null);
  }
}
