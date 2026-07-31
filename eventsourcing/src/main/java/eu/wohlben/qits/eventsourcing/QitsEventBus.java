package eu.wohlben.qits.eventsourcing;

import eu.wohlben.qits.eventsourcing.control.EventEnvelope;
import eu.wohlben.qits.eventsourcing.control.EventsPublisher;
import eu.wohlben.qits.eventsourcing.control.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Publish an event. The whole producing API: one method, no channel, no topic, no configuration at
 * the call site.
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
 */
@ApplicationScoped
public class QitsEventBus {

  private static final Logger LOG = Logger.getLogger(QitsEventBus.class);

  @Inject EventsPublisher publisher;

  @Inject Outbox outbox;

  @ConfigProperty(name = "qits.eventsourcing.enabled")
  boolean enabled;

  /** Announce that something happened. Returns as soon as the event is either delivered or owned. */
  public void publish(QitsEvent event) {
    if (!enabled) {
      LOG.debugf("eventsourcing disabled: %s %s not published", event.signature(), event.eventId());
      return;
    }
    String eventId = event.eventId().toString();
    try {
      EventEnvelope envelope = EventEnvelope.of(event);
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
