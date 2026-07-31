package eu.wohlben.qits.eventsourcing;

import eu.wohlben.qits.eventsourcing.control.EventEnvelope;
import eu.wohlben.qits.eventsourcing.control.EventsPublisher;
import eu.wohlben.qits.eventsourcing.control.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Publish an event. The whole producing API: one method and one overload of it, no channel, no
 * topic, no configuration at the call site.
 *
 * <p><b>Fire-and-forget for the caller, durable for the event.</b> {@link #publish} attempts the
 * idempotent {@code PUT} inline and returns; if that does not land, the event goes to the outbox
 * and a scheduled sweeper owns it from there. So a caller never sees a delivery failure and never
 * has to decide what to do about one — which is what lets the publish hook sit in the middle of a
 * business transition without becoming a way for that transition to fail.
 *
 * <p>It does not throw. The two things that could — an event class Jackson cannot serialize, and a
 * database that will not take the outbox row — are logged and dropped, because an event is an
 * announcement and losing one must never be worse for the caller than the thing it was announcing.
 * An unserializable event is a programming error that shows up as a warning on the first publish,
 * which is the right amount of noise for something no retry would fix.
 *
 * <p><b>And it writes no row for either, which was weighed rather than overlooked.</b> The
 * unserializable case stopped being hypothetical the day a native image could not reflect on the
 * event class, and "at least record the loss" was the obvious repair. It does not fit: the
 * serializer is what threw, so there is no {@code payload} — the one thing an outbox row exists to
 * hold — and a {@code FAILED} row with a null payload is one the sweeper can never retry and a
 * dead-letter view could never resend. In the second case, writing a row is the identical database
 * write that just failed. The outbox is what has <em>not arrived yet</em>, which is what lets its
 * row count be a health signal rather than a log; the record of an event that could not be built is
 * the warning below, carrying the id and the signature, and that warning is how the native-image gap
 * was actually found.
 *
 * <p>Disabled ({@code qits.eventsourcing.enabled=false}) it is a debug log and nothing else: no
 * request, no row, no subscriber. That is the {@code %dev}/{@code %test} posture the platform gives
 * every external telemetry-shaped dependency, and it means a test suite that never configured
 * qits-events makes no dials rather than five retries' worth.
 *
 * <p><b>Causation is stamped here, and nowhere else.</b> The envelope's {@code parentId} is resolved
 * at the one point an envelope is built — {@link #publish(QitsEvent, UUID)} — from an explicit
 * argument if there is one and from {@link CausationScope} otherwise. Deliberately <em>not</em> a
 * fifth {@link QitsEvent} method: an event record is immutable and its parent is known later than
 * it is, a default method reading a thread-local would answer differently on the sweeper's thread
 * an hour later (which is precisely what {@code eventId}'s stability argument forbids), and a fifth
 * accessor would need a fifth {@code @JsonIgnore} in the one place this repo has already been bitten
 * silently. Identity travels in the envelope; so does causation.
 */
@ApplicationScoped
public class QitsEventBus {

  private static final Logger LOG = Logger.getLogger(QitsEventBus.class);

  @Inject EventsPublisher publisher;

  @Inject Outbox outbox;

  @ConfigProperty(name = "qits.eventsourcing.enabled")
  boolean enabled;

  /**
   * Announce that something happened. Returns as soon as the event is either delivered or owned.
   *
   * <p>Exactly {@code publish(event, null)} — there is one implementation and one call shape. The
   * cause, if there is one, comes from {@link CausationScope}.
   */
  public void publish(QitsEvent event) {
    publish(event, null);
  }

  /**
   * Announce that something happened, caused by the event with this id.
   *
   * <p><b>The precedence rule, whole:</b> an explicit non-null {@code parentEventId} wins; a null
   * one means "unspecified" and falls back to {@link CausationScope#current()}; outside any scope
   * that is null too, and the event is a chain root. An author who names a cause knows something the
   * runtime does not — typically that the causation crossed a thread and a database row on its way
   * here, which no thread-local could have carried.
   *
   * <p>Note the asymmetry with the scope API, which is deliberate and was settled rather than
   * overlooked: {@code publish(event, null)} means "I have no argument to pass", while {@link
   * CausationScope#with(UUID, Runnable) with(null, …)} means "in this region nothing is the cause".
   * The detach is a statement about a region; the price of saying it there is that {@code publish}
   * keeps a single shape.
   *
   * <p><b>No self-parent repair and no cycle guard.</b> A guard that catches only {@code A → A}
   * cannot see {@code A → B → A}, and its presence would tell a reader that cycles are handled.
   * Detection belongs where the graph is visible; qits-events does reject a self-edge, because that
   * one is decidable from a single row.
   *
   * @param parentEventId the event that caused this one, or null for "unspecified"
   */
  public void publish(QitsEvent event, UUID parentEventId) {
    if (!enabled) {
      LOG.debugf("eventsourcing disabled: %s %s not published", event.signature(), event.eventId());
      return;
    }
    String eventId = event.eventId().toString();
    try {
      UUID parent = parentEventId != null ? parentEventId : CausationScope.current();
      EventEnvelope envelope = EventEnvelope.of(event, parent);
      EventsPublisher.Delivery attempt = publisher.put(eventId, envelope);
      if (attempt.delivered()) {
        return;
      }
      // Everything that is not a delivery becomes a row, including the unretryable 400 — a
      // rejection that left no trace would be the one failure nobody could investigate.
      outbox.enqueue(eventId, envelope, attempt);
    } catch (Exception e) {
      LOG.warnf(e, "event %s (%s) could not be published or recorded", eventId, event.signature());
    }
  }
}
