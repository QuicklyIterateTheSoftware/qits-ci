package eu.wohlben.qits.eventsourcing;

/**
 * Consumes one event type arriving from the bus. Implementations are ordinary {@code
 * @ApplicationScoped} beans; the subscriber collects every one of them at startup, derives the
 * subscription set from their {@link #eventType()}s and dispatches incoming frames by signature.
 * Registering a listener is therefore exactly "add a bean", with no channel name, no annotation and
 * no configuration.
 *
 * <p>Several listeners may take the same type; each gets the event. Zero listeners in the whole
 * application means the subscriber never dials at all — there is nothing to subscribe to.
 *
 * <p>A plain {@code @ApplicationScoped} bean is enough and no {@code @Unremovable} is needed: an
 * {@code Instance<QitsEventListener<?>>} injection point is what ArC's unused-bean removal counts
 * as a use. Worth knowing rather than assuming, because a listener that <em>was</em> removed
 * subscribes to nothing, receives nothing and logs nothing — so the eventsourcing suite keeps a
 * listener that is injected nowhere and asserts it turns up in the subscription set.
 *
 * <p><b>Delivery is at-most-once and the stream is live-only.</b> A listener is not a queue
 * consumer: it sees what is broadcast while it is connected, and events that occurred during a
 * disconnect are not replayed. Catch-up from the event log is a separate feature. So a listener may
 * do anything that tolerates being skipped, and nothing that must happen exactly once.
 *
 * <p>{@link #onEvent} runs on a worker thread, one frame at a time per connection, and a throw is
 * logged and swallowed — it must not take the socket down with it. Anything slow belongs on the
 * listener's own executor.
 *
 * <p><b>Which is exactly where causation is dropped, so the bridge is part of the advice.</b> The
 * dispatcher runs {@link #onEvent} inside {@link CausationScope} of the arriving event's id, so
 * anything published <em>on this thread</em> is stamped with it and no argument is needed. A hand-off
 * to your own executor leaves that scope behind — a plain {@code ThreadLocal} does not follow work,
 * and deliberately so — so capture and re-establish it:
 *
 * <pre>{@code
 * public void onEvent(ThingHappened event) {
 *   UUID cause = CausationScope.current();
 *   executor.submit(() -> CausationScope.with(cause, () -> {
 *     bus.publish(new SomethingFollowed(...));
 *   }));
 * }
 * }</pre>
 *
 * <p>Or pass it: {@code bus.publish(followUp, cause)} says the same thing at one site instead of a
 * region, and is the better shape when the code that knows the cause is the code that publishes.
 * What is <em>not</em> an option is doing neither and believing the chain was recorded — a dropped
 * parent is a root event, which is a silent, unbackfillable loss.
 */
public interface QitsEventListener<E extends QitsEvent> {

  /**
   * The event class this listener wants. Its simple name is the signature subscribed to, and it is
   * what the frame's payload is deserialized into — so it must be constructible from the payload
   * alone (a record with a Jackson-visible creator is the shape to reach for).
   */
  Class<E> eventType();

  /** One arrival. */
  void onEvent(E event);
}
