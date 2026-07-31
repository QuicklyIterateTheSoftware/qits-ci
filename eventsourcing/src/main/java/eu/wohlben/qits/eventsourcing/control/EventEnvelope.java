package eu.wohlben.qits.eventsourcing.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of {@code PUT /events/api/events/{id}} — and, minus the id qits-events adds, the same
 * shape that comes back out of {@code /events/stream}. The frozen wire contract:
 *
 * <pre>{@code
 * {"description":null,"name":"BuildSuccessful","occurredAt":"2026-07-31T12:46:03Z",
 *  "parentId":"6c3f2b1a-…","payload":"{…}"}
 * }</pre>
 *
 * <p>{@code name} doubles as the signature; {@code payload} is the event's fields as a canonical
 * JSON <em>string</em> (a string, not a nested object — the server stores and compares it verbatim
 * and never has to parse it); {@code description} is the human account, which an event published
 * this way does not have; {@code parentId} is the id of the event this one was caused by, or null
 * when it is a chain root.
 *
 * <p>The {@code @JsonInclude(ALWAYS)} is the one deliberate exception to {@link CanonicalJson}'s
 * omit-nulls rule and belongs on this type rather than in the mapper: the contract spells {@code
 * description} and {@code parentId} as explicit nulls, and an envelope that quietly dropped either
 * key would be a different document than the one both sides were written against. The annotation is
 * on the <em>type</em>, so a component added here inherits it without further thought, and key order
 * needs none either — the shared mapper sorts alphabetically, so a new key simply lands where its
 * name puts it.
 *
 * <p><b>{@code parentId} is a {@link String} on the wire, and it is envelope rather than payload.</b>
 * A string because every id in this contract is one — the path id, the frame's {@code id},
 * qits-events' own {@code varchar(255)} column — and the {@link UUID} the API takes is converted
 * here, at the boundary. Envelope for a reason stronger than symmetry with {@code eventId}: the
 * payload string is compared byte-for-byte by the server, so a causation field that had entered it
 * would make one event published under two different parents into two events nothing could
 * reconcile. Because no event class ever declares the field, {@link CanonicalJson} and its mix-in
 * needed no change at all — there is nothing new to hide.
 *
 * <p>It <em>does</em> participate in the server's idempotency comparison ({@code name} + {@code
 * occurredAt} + {@code payload} + {@code parentId}; {@code description} stays outside). That is why
 * the outbox stores the value rather than re-deriving it: a retry rebuilt without the parent would
 * either 400 against its own landed first attempt or land as a root.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EventEnvelope(
    String name, Instant occurredAt, String payload, String description, String parentId) {

  /** The envelope for an event with no cause — the root case, spelled without an argument. */
  public static EventEnvelope of(QitsEvent event) {
    return of(event, null);
  }

  /**
   * The envelope for an event: its name, its time, its canonicalized fields, no description, and
   * the cause it is being published under. Built in exactly one place on the publishing path, which
   * is what makes the stamping one method to read and one to test — and what gets the parent into
   * the outbox row for free, since a row is built from an envelope.
   *
   * @param parentEventId the resolved cause — the caller's explicit argument or {@link
   *     CausationScope}'s ambient value, already decided by {@code QitsEventBus} — or null for an
   *     event nothing caused
   */
  public static EventEnvelope of(QitsEvent event, UUID parentEventId) {
    return new EventEnvelope(
        event.name(),
        event.occurredAt(),
        CanonicalJson.payload(event),
        null,
        parentEventId == null ? null : parentEventId.toString());
  }
}
