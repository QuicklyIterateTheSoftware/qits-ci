package eu.wohlben.qits.eventsourcing.control;

import java.time.Instant;

/**
 * One text frame pushed by qits-events on {@code /events/stream}: the envelope plus the row's id.
 *
 * <p>The id is here because a live-only stream is not the end of the design — catch-up from the
 * event log is the next feature, and an envelope that carries its identity is what lets a consumer
 * one day say "everything after this one" without the frame format changing. Nothing in this module
 * uses it beyond logging today, and that is deliberate rather than an omission.
 *
 * <p>Unknown fields are ignored (see {@link CanonicalJson}): a qits-events that grows a field must
 * not stop this subscriber from reading the ones it already understood.
 */
public record EventFrame(
    String id, String name, Instant occurredAt, String payload, String description) {}
