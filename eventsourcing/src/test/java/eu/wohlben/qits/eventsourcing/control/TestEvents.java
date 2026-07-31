package eu.wohlben.qits.eventsourcing.control;

import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import eu.wohlben.qits.eventsourcing.QitsEventBus;
import eu.wohlben.qits.eventsourcing.QitsEventListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

  /** A listener that only records — what it received, and the cause it was dispatched under. */
  @ApplicationScoped
  public static class ThingListener implements QitsEventListener<ThingHappened> {

    private final List<ThingHappened> received = new CopyOnWriteArrayList<>();
    private final List<Optional<UUID>> causes = new CopyOnWriteArrayList<>();

    @Override
    public Class<ThingHappened> eventType() {
      return ThingHappened.class;
    }

    @Override
    public void onEvent(ThingHappened event) {
      // Read INSIDE onEvent, which is the only place the answer means anything: the dispatcher's
      // scope is established for the call and unwound after it.
      causes.add(Optional.ofNullable(CausationScope.current()));
      received.add(event);
    }

    public List<ThingHappened> received() {
      return List.copyOf(received);
    }

    /** The ambient cause seen on each arrival, empty where there was none. */
    public List<Optional<UUID>> causes() {
      return List.copyOf(causes);
    }

    public void reset() {
      received.clear();
      causes.clear();
    }
  }

  /**
   * A second listener on {@link ThingHappened}, armed by the test that wants it and inert otherwise.
   *
   * <p>It exists for three things the dispatcher has to get right and that only a listener can
   * observe from the inside: that two listeners on one frame see the <em>same</em> cause, that an
   * event published <em>during</em> consumption is stamped with the arriving frame's id without
   * anybody passing an argument, and that a listener which throws does not leak its scope onto the
   * next frame.
   *
   * <p>On {@link ThingHappened} rather than on a fourth event type on purpose: the subscription set
   * is asserted literally in {@code EventStreamSubscriberTest}, and a new signature would have made
   * this bean a change to that contract instead of an addition to this one. Disarmed by default for
   * the same reason — every other test in the suite dispatches this signature.
   */
  @ApplicationScoped
  public static class CausationProbeListener implements QitsEventListener<ThingHappened> {

    @Inject QitsEventBus bus;

    private final List<Optional<UUID>> causes = new CopyOnWriteArrayList<>();
    private final AtomicBoolean publishOnEvent = new AtomicBoolean();
    private final AtomicBoolean throwOnEvent = new AtomicBoolean();
    private final AtomicReference<OtherThingHappened> published = new AtomicReference<>();

    @Override
    public Class<ThingHappened> eventType() {
      return ThingHappened.class;
    }

    @Override
    public void onEvent(ThingHappened event) {
      causes.add(Optional.ofNullable(CausationScope.current()));
      if (publishOnEvent.get()) {
        // No parent argument anywhere: whatever lands on the wire came from the ambient scope.
        OtherThingHappened followUp = new OtherThingHappened("because of " + event.what(), event.at());
        published.set(followUp);
        bus.publish(followUp);
      }
      if (throwOnEvent.get()) {
        throw new IllegalStateException("a listener that fails mid-frame");
      }
    }

    /** Publish a follow-up event from inside {@code onEvent}, with no explicit parent. */
    public void publishWhileConsuming() {
      publishOnEvent.set(true);
    }

    /** Throw out of {@code onEvent}, after having recorded the cause. */
    public void failWhileConsuming() {
      throwOnEvent.set(true);
    }

    /** The follow-up this listener last published, so a test can name its id. */
    public OtherThingHappened published() {
      return published.get();
    }

    public List<Optional<UUID>> causes() {
      return List.copyOf(causes);
    }

    public void reset() {
      causes.clear();
      publishOnEvent.set(false);
      throwOnEvent.set(false);
      published.set(null);
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
