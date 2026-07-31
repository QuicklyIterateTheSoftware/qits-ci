package eu.wohlben.qits.eventsourcing;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened, worth telling the rest of the platform about. Implementations are plain
 * data — a record or a small final class — and their <b>fields are the payload</b>: {@link
 * QitsEventBus} canonicalizes them to JSON and the four methods declared here are excluded from it,
 * so an implementation never has to think about which of its members are wire and which are
 * envelope.
 *
 * <p>Deliberately not a CDI event type and deliberately not a reactive-messaging message. Firing a
 * bare {@code jakarta.enterprise.event.Event} would make a remote arrival indistinguishable from a
 * local one at every {@code @Observes} site — a service that both fires a type locally and
 * round-trips it through the bus would deliver it twice — and a reactive-messaging connector is
 * real machinery for a first cut. The reasoning is in the superproject's eventsourcing-plan.md; the
 * consequence is that this interface is the whole contract and the transport is free to change
 * underneath it.
 */
public interface QitsEvent {

  /**
   * How this event type is named on the wire, and what a subscriber subscribes to. Defaults to the
   * simple class name, which is what qits-events stores in the event row's {@code name} column.
   *
   * <p><b>An override has to stay in step with the class name.</b> A subscription set is built from
   * {@link QitsEventListener#eventType()}, which is a {@link Class} and has no instance to ask — so
   * the subscriber derives the signature as {@code eventType().getSimpleName()}. Overriding this
   * without renaming the class means publishing under one name and listening for another, which
   * fails silently in the only way this design can fail silently.
   */
  default String signature() {
    return getClass().getSimpleName();
  }

  /**
   * This occurrence's identity: a UUID v4 <b>generated once, at construction, and stable for the
   * object's life</b>. It is the {@code {id}} of the idempotent {@code PUT}, which is the entire
   * reason a retry is safe — a publish that half-succeeded (the server wrote the row, the response
   * was lost) replays as a 200 rather than duplicating the event.
   *
   * <p>Regenerating it per call, or deriving it from the payload, breaks that in opposite
   * directions: the first duplicates on every retry, the second makes two genuinely distinct
   * occurrences collide as one.
   */
  UUID eventId();

  /** When the thing happened, as the publisher's clock saw it. Travels as the envelope's {@code occurredAt}. */
  Instant occurredAt();

  /**
   * The short label the event log shows. Defaults to {@link #signature()} and there is presently no
   * reason to override it — qits-events keeps {@code name} as the signature and {@code description}
   * as the human account.
   */
  default String name() {
    return signature();
  }
}
