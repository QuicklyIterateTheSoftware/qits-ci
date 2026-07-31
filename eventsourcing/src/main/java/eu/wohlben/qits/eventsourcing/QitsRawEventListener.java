package eu.wohlben.qits.eventsourcing;

import eu.wohlben.qits.eventsourcing.control.EventFrame;
import java.util.Set;

/**
 * Consumes arriving frames <em>as frames</em>, for a set of signatures the listener names at runtime
 * rather than by declaring a type. The seam for consumers whose interest is <b>not knowable at
 * startup</b>: a trigger engine whose selections live in files inside other repositories, an audit
 * sink, anything config-driven. Implementations are ordinary {@code @ApplicationScoped} beans and
 * are discovered exactly as {@link QitsEventListener}s are.
 *
 * <p><b>Use {@link QitsEventListener} unless you cannot.</b> The typed seam gives you a
 * deserialized event class and a compile-time contract with its author; this one gives you the wire.
 * A raw listener that could have named its event type is a typed listener with extra steps.
 *
 * <h2>{@link #signatures()} and the {@code "*"} literal</h2>
 *
 * <p>The set is the event <em>names</em> this listener wants, and the literal {@link #ALL} means
 * every name there is. The subscription frame sent to qits-events is the <b>union</b> of every typed
 * listener's signature and every raw listener's set, and {@link #ALL} anywhere in that union
 * collapses it to {@code ["*"]} — one listener asking for everything makes narrowing the wire
 * pointless, and a subscription set is a filter rather than a promise, so the wider set costs only
 * frames that dispatch then drops.
 *
 * <p><b>It is asked often and must be cheap: once per subscribe, and once per arriving frame.</b>
 * That is what makes the set dynamic — a trigger engine that learns a new event name from a push
 * starts receiving it on the next frame, with no restart. What it does <em>not</em> do is resubscribe:
 * the wire set is re-derived when the connection is opened, so a widened set reaches qits-events at
 * the next reconnect and not before. <b>A listener whose interest can grow should therefore return
 * {@code Set.of(ALL)} permanently</b> and filter in {@link #onFrame}; that is the whole idiom this
 * seam exists for, and it is why "unknowable at startup" is not a problem the wire has to solve.
 *
 * <p>A {@code signatures()} that throws is logged and treated as an empty set for that one question
 * — the listener gets nothing rather than the stream getting nothing.
 *
 * <h2>Delivery</h2>
 *
 * <p><b>Typed listeners first, raw listeners second</b>, deterministically. Both paths run for a
 * frame both want, each gets it once, and neither can prevent the other: a throw out of {@link
 * #onFrame} is logged and swallowed exactly as one out of {@code onEvent} is, because the caller is
 * a socket callback and losing one event is the designed failure here while losing the stream is
 * not.
 *
 * <p>Everything {@link QitsEventListener}'s javadoc says about consumption holds here unchanged:
 * delivery is at-most-once and live-only, {@link #onFrame} runs on a worker thread one frame at a
 * time, and anything slow belongs on the listener's own executor.
 *
 * <p><b>Causation is identical too.</b> {@link #onFrame} runs inside {@link CausationScope} of the
 * arriving frame's id — the same single scope the typed listeners on that frame run in — so an event
 * published while consuming records this one as its parent with nobody passing an argument. A hand-off
 * to your own executor leaves that scope behind; capture {@link CausationScope#current()} and
 * re-establish it, or pass the id to {@code publish}. The frame's own {@link EventFrame#parentId()}
 * is the <em>previous</em> hop and is not what gets stamped.
 *
 * <p>A raw listener that enqueues work for another thread — which is what a trigger engine does — is
 * past the reach of any thread-local by construction. Carry {@link EventFrame#id()} on whatever it
 * enqueues and hand it to {@code publish(event, parent)} at the far end; that is the durable form of
 * the same edge.
 */
public interface QitsRawEventListener {

  /** The signature that means "every signature". See the class javadoc for what it does to the union. */
  String ALL = "*";

  /**
   * The event names wanted right now, or {@code Set.of(ALL)} for all of them. Asked once per
   * subscribe and once per frame, so it must be cheap and should not block.
   */
  Set<String> signatures();

  /**
   * One arrival, as it came off the wire: the envelope plus the row's id. The payload is the
   * canonical JSON string qits-events stored — parse it yourself, or do not.
   */
  void onFrame(EventFrame frame);
}
