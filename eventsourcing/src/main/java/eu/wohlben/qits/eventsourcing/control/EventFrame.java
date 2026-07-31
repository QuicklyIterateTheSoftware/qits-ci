package eu.wohlben.qits.eventsourcing.control;

import java.time.Instant;

/**
 * One text frame pushed by qits-events on {@code /events/stream}: the envelope plus the row's id.
 *
 * <p>The id is here because a live-only stream is not the end of the design — catch-up from the
 * event log is the next feature, and an envelope that carries its identity is what lets a consumer
 * one day say "everything after this one" without the frame format changing.
 *
 * <p><b>This record is public because {@link eu.wohlben.qits.eventsourcing.QitsRawEventListener}
 * receives it.</b> It is the wire as it arrived and it is handed over unchanged and unshared-per-
 * listener — every raw listener on one frame gets the same instance, which is safe because a record
 * of strings is immutable. A typed listener never sees it: that path deserializes {@code payload}
 * into the event class and the frame stays inside {@link EventDispatcher}.
 *
 * <p>Unknown fields are ignored (see {@link CanonicalJson}): a qits-events that grows a field must
 * not stop this subscriber from reading the ones it already understood.
 *
 * <p><b>{@code parentId} is what this module's causation stamping reads on the way in</b>, and it is
 * appended last rather than placed beside the envelope's copy of it because both sides bind by
 * <em>name</em>: component order here is not a contract, and the same {@code
 * FAIL_ON_UNKNOWN_PROPERTIES}-off that lets a subscriber built against five fields survive a sixth
 * is what makes an older qits-events sending five still bind (the missing one arrives as null). It
 * is the frame's {@code id} rather than this field that {@link EventDispatcher} scopes a listener
 * with — the arriving event is the cause of whatever the listener publishes, and its own parent is
 * the previous hop's business.
 */
public record EventFrame(
    String id,
    String name,
    Instant occurredAt,
    String payload,
    String description,
    String parentId) {}
