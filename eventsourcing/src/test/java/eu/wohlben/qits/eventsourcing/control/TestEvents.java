package eu.wohlben.qits.eventsourcing.control;

import eu.wohlben.qits.eventsourcing.QitsEvent;
import eu.wohlben.qits.eventsourcing.QitsEventListener;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The event types and listeners this module's own suite uses.
 *
 * <p><b>Deliberately not {@code BuildSuccessful}.</b> That class lives in {@code ci-events}, which
 * depends on this module — reaching for it here would invert the arrow the extraction rule exists
 * to protect, and would make the library's tests depend on one consumer's vocabulary. These three
 * types are what a library test should exercise: something with a couple of fields, something else
 * so a subscription set is a set, and one whose listener nothing injects.
 */
public final class TestEvents {

  /** A two-field event with a nullable member, so the absent-field rule is exercised on the wire. */
  public record ThingHappened(UUID eventId, String what, Integer count, Instant at)
      implements QitsEvent {

    public ThingHappened {
      if (eventId == null) {
        eventId = UUID.randomUUID();
      }
    }

    public ThingHappened(String what, Integer count, Instant at) {
      this(null, what, count, at);
    }

    @Override
    public Instant occurredAt() {
      return at;
    }
  }

  /** A second type, so the subscribe frame has to carry a set rather than a name. */
  public record OtherThingHappened(UUID eventId, String detail, Instant at) implements QitsEvent {

    public OtherThingHappened {
      if (eventId == null) {
        eventId = UUID.randomUUID();
      }
    }

    public OtherThingHappened(String detail, Instant at) {
      this(null, detail, at);
    }

    @Override
    public Instant occurredAt() {
      return at;
    }
  }

  /**
   * A third type whose listener is <b>injected nowhere</b>, on purpose. A real consumer's listener
   * is like this: a bean written to be found, reached only through {@code
   * Instance<QitsEventListener<?>>}, with no other reference to it in the application. That is
   * exactly the shape ArC's unused-bean removal exists for, and a listener silently removed fails
   * in the worst available way — it subscribes to nothing, receives nothing, and logs nothing. Its
   * presence in the subscription set is asserted in {@code EventStreamSubscriberTest}.
   */
  public record QuietlyHappened(UUID eventId, Instant at) implements QitsEvent {

    public QuietlyHappened {
      if (eventId == null) {
        eventId = UUID.randomUUID();
      }
    }

    @Override
    public Instant occurredAt() {
      return at;
    }
  }

  /** A listener that only records. */
  @ApplicationScoped
  public static class ThingListener implements QitsEventListener<ThingHappened> {

    private final List<ThingHappened> received = new CopyOnWriteArrayList<>();

    @Override
    public Class<ThingHappened> eventType() {
      return ThingHappened.class;
    }

    @Override
    public void onEvent(ThingHappened event) {
      received.add(event);
    }

    public List<ThingHappened> received() {
      return List.copyOf(received);
    }

    public void reset() {
      received.clear();
    }
  }

  /** The second listener, on the second type. */
  @ApplicationScoped
  public static class OtherThingListener implements QitsEventListener<OtherThingHappened> {

    private final List<OtherThingHappened> received = new CopyOnWriteArrayList<>();

    @Override
    public Class<OtherThingHappened> eventType() {
      return OtherThingHappened.class;
    }

    @Override
    public void onEvent(OtherThingHappened event) {
      received.add(event);
    }

    public List<OtherThingHappened> received() {
      return List.copyOf(received);
    }

    public void reset() {
      received.clear();
    }
  }

  /** The listener nobody injects. See {@link QuietlyHappened}. */
  @ApplicationScoped
  public static class QuietListener implements QitsEventListener<QuietlyHappened> {

    @Override
    public Class<QuietlyHappened> eventType() {
      return QuietlyHappened.class;
    }

    @Override
    public void onEvent(QuietlyHappened event) {
      // Nothing. Being registered is the whole of what it is here to prove.
    }
  }

  private TestEvents() {}
}
