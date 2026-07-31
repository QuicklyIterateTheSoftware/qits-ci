package eu.wohlben.qits.eventsourcing.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import eu.wohlben.qits.eventsourcing.QitsEventListener;
import eu.wohlben.qits.eventsourcing.QitsRawEventListener;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The registry of listener beans — {@link QitsEventListener} and {@link QitsRawEventListener} — and
 * the routing of one arriving frame to them.
 *
 * <p>Two jobs that are really one: the set of signatures to subscribe to and what dispatch will
 * route are the <em>same</em> derivation from the same beans, so a listener cannot be subscribed for
 * and then not delivered to, or vice versa. That symmetry is the reason this is not two classes, and
 * it survives the raw seam: {@link #signatures()} and {@link #dispatch} both read {@link
 * QitsRawEventListener#signatures()} live rather than caching an answer one of them could then
 * disagree with.
 *
 * <p><b>Not CDI events.</b> Delivery is a direct call on the listener bean. Routing remote arrivals
 * through {@code @Observes} would make them indistinguishable from locally fired events of the same
 * type at every observer site — the plan's {@code @FromEventBus} qualifier exists to fix that if
 * CDI delivery were ever wanted, and it is not needed while the interface is the contract.
 *
 * <p><b>The beans are resolved once, at first use; the raw listeners' signature sets are not.</b>
 * Which bean exists is a build-time fact. What a raw listener wants is deliberately a runtime one —
 * that is the seam's whole purpose — so it is asked per subscribe and per frame. The consequence
 * worth stating: a widened raw set takes effect for dispatch immediately and reaches the wire only
 * at the next reconnect, which is why {@link QitsRawEventListener}'s javadoc tells a consumer with a
 * growing interest to say {@code "*"} once and filter for itself.
 *
 * <h2>The subscription union, and what {@code "*"} does to it</h2>
 *
 * <p>The frame sent to qits-events is the union of every typed listener's signature and every raw
 * listener's set, sorted. {@link QitsRawEventListener#ALL} anywhere in that union <b>collapses it to
 * {@code ["*"]}</b>: once one consumer wants everything, narrowing the wire buys nothing, and the
 * extra frames are dropped here at no cost to anyone else.
 *
 * <h2>Dispatch order: typed first, raw second</h2>
 *
 * <p>Stated because it is a contract rather than an accident. Typed dispatch is the path that
 * already existed and its consumers are the domain handlers for a specific event; the raw seam is
 * open-ended, cross-cutting and, in its motivating case, does real work per frame. Putting it second
 * is what makes "typed dispatch is unchanged" true in the only way that is observable from a typed
 * listener — when it is called relative to the frame's arrival. Both paths run for a frame both want,
 * each listener gets it once, and a throw anywhere in either is contained, so neither path can
 * shorten the other.
 */
@ApplicationScoped
public class EventDispatcher {

  private static final Logger LOG = Logger.getLogger(EventDispatcher.class);

  @Inject @Any Instance<QitsEventListener<?>> listenerBeans;

  @Inject @Any Instance<QitsRawEventListener> rawListenerBeans;

  /** Signature → the listeners wanting it. Sorted, so the subscribe frame is stable across boots. */
  private final Map<String, List<QitsEventListener<?>>> bySignature = new TreeMap<>();

  /** Every raw listener bean. Which ones want a given frame is asked of them, per frame. */
  private final List<QitsRawEventListener> rawListeners = new ArrayList<>();

  @PostConstruct
  void index() {
    for (QitsEventListener<?> listener : listenerBeans) {
      // The signature is derived from the CLASS, since there is no instance to ask — which is the
      // constraint QitsEvent#signature()'s javadoc warns about from the other side.
      String signature = listener.eventType().getSimpleName();
      bySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(listener);
    }
    for (QitsRawEventListener raw : rawListenerBeans) {
      rawListeners.add(raw);
    }
    if (!bySignature.isEmpty()) {
      LOG.debugf("event listeners registered for %s", bySignature.keySet());
    }
    if (!rawListeners.isEmpty()) {
      LOG.debugf("%d raw event listeners registered", rawListeners.size());
    }
  }

  /**
   * What to put in the subscribe frame, in a stable order: the union of the typed signatures and
   * whatever the raw listeners currently want, collapsed to {@code ["*"]} if any of them wants
   * everything. Empty means there is no reason to open a stream at all.
   */
  public List<String> signatures() {
    return union(bySignature.keySet(), rawWants());
  }

  /**
   * The union rule, in one place and testable without a container: sorted, de-duplicated, and
   * {@code ["*"]} the moment {@link QitsRawEventListener#ALL} appears anywhere in it. Null and blank
   * entries are dropped rather than propagated — a listener that names nothing wants nothing, and a
   * null on the wire would be a frame qits-events could not read.
   */
  static List<String> union(
      Collection<String> typed, Collection<? extends Collection<String>> raw) {
    Set<String> all = new TreeSet<>();
    add(all, typed);
    for (Collection<String> wanted : raw) {
      add(all, wanted);
    }
    return all.contains(QitsRawEventListener.ALL) ? List.of(QitsRawEventListener.ALL) : List.copyOf(all);
  }

  private static void add(Set<String> into, Collection<String> from) {
    if (from == null) {
      return;
    }
    for (String signature : from) {
      if (signature != null && !signature.isBlank()) {
        into.add(signature);
      }
    }
  }

  /**
   * Route one frame. Never throws: the caller is a socket callback, and neither an unparseable
   * frame, an unknown signature, a payload that will not deserialize nor a listener that threw may
   * take the connection down — losing one event is the designed failure here, losing the stream is
   * not.
   *
   * <p><b>An unreadable frame is a WARN, and it used to be a DEBUG.</b> The two failures are not
   * comparable: an unknown signature below is ordinary traffic, while a frame this module asked for
   * and then could not read is a defect in the frame format, in the far side, or — the case that
   * bought this line — in the native image's reflection metadata. At DEBUG a binary that could not
   * deserialize {@link EventFrame} at all said <em>nothing</em>, on every frame, for as long as it
   * ran. Dropping it stays right; being quiet about it did not.
   *
   * <p><b>The listener loop runs inside {@link CausationScope} of the arriving frame's id</b>, so an
   * event a listener publishes while consuming records this one as its parent with nobody passing an
   * argument. One scope for the whole loop rather than one per listener: every listener on a frame
   * is running because of the same arrival, so they see the same cause — <b>and that is one scope
   * across both paths</b>, since a raw listener consumes the same arrival for the same reason. The
   * scope is unwound before this method returns — including when a listener throws, since {@code
   * with} restores in a {@code finally} — which is what keeps a stale parent off the next frame on a
   * worker thread that is reused for the life of the process.
   *
   * <p>Typed listeners are called before raw ones; see the class javadoc for why that is a decision
   * rather than an ordering that happened.
   */
  public void dispatch(String text) {
    EventFrame frame;
    try {
      frame = CanonicalJson.frame(text);
    } catch (RuntimeException e) {
      LOG.warnf("dropped an unreadable frame [%s]: %s", describe(text), e.getMessage());
      return;
    }
    List<QitsEventListener<?>> listeners = bySignature.get(frame.name());
    List<QitsRawEventListener> raw = rawListenersFor(frame.name());
    if (listeners == null && raw.isEmpty()) {
      // Ordinary: the server may broadcast more than was asked for, and a subscription set is a
      // filter rather than a promise.
      LOG.debugf("no listener for signature %s", frame.name());
      return;
    }
    CausationScope.with(causeOf(frame), () -> deliver(frame, listeners, raw));
  }

  private void deliver(
      EventFrame frame, List<QitsEventListener<?>> listeners, List<QitsRawEventListener> raw) {
    if (listeners != null) {
      deliverTyped(frame, listeners);
    }
    deliverRaw(frame, raw);
  }

  @SuppressWarnings("unchecked")
  private void deliverTyped(EventFrame frame, List<QitsEventListener<?>> listeners) {
    for (QitsEventListener<?> listener : listeners) {
      try {
        QitsEvent event =
            (QitsEvent) CanonicalJson.payloadTo(frame.payload(), listener.eventType());
        ((QitsEventListener<QitsEvent>) listener).onEvent(event);
      } catch (Exception e) {
        LOG.errorf(
            e, "listener %s failed on %s", listener.getClass().getName(), frame.name());
      }
    }
  }

  /** Same containment as the typed loop, and for the same reason: the caller is a socket callback. */
  private void deliverRaw(EventFrame frame, List<QitsRawEventListener> listeners) {
    for (QitsRawEventListener listener : listeners) {
      try {
        listener.onFrame(frame);
      } catch (Exception e) {
        LOG.errorf(
            e, "raw listener %s failed on %s", listener.getClass().getName(), frame.name());
      }
    }
  }

  /** The raw listeners that want this signature right now — asked of each one, per frame. */
  private List<QitsRawEventListener> rawListenersFor(String name) {
    if (rawListeners.isEmpty()) {
      return List.of();
    }
    List<QitsRawEventListener> wanting = new ArrayList<>(rawListeners.size());
    for (QitsRawEventListener listener : rawListeners) {
      Set<String> wanted = wantedBy(listener);
      if (wanted.contains(QitsRawEventListener.ALL) || wanted.contains(name)) {
        wanting.add(listener);
      }
    }
    return wanting;
  }

  /** Every raw listener's current set, in bean order. */
  private List<Set<String>> rawWants() {
    List<Set<String>> wants = new ArrayList<>(rawListeners.size());
    for (QitsRawEventListener listener : rawListeners) {
      wants.add(wantedBy(listener));
    }
    return wants;
  }

  /**
   * What one raw listener wants, contained. A {@code signatures()} that throws or answers null costs
   * that listener the frame (or its place in the subscribe set) and costs nobody else anything — the
   * same trade the delivery loops make, one question earlier.
   */
  private static Set<String> wantedBy(QitsRawEventListener listener) {
    try {
      Set<String> wanted = listener.signatures();
      return wanted == null ? Set.of() : wanted;
    } catch (Exception e) {
      LOG.errorf(e, "raw listener %s could not name its signatures", listener.getClass().getName());
      return Set.of();
    }
  }

  /**
   * The arriving event's id as a {@link UUID}, or null if it is not one.
   *
   * <p>The frame's {@code id} is a {@link String} — qits-events' column is a {@code varchar(255)}
   * with no format promise attached — and this is the one place in the module that has to make a
   * UUID of it. <b>Unparseable means "no cause", not "drop the frame".</b> Dropping an event is a
   * designed failure here; refusing to dispatch over an id format is not, and a throw out of this
   * method would take out every listener on the frame for the sake of a field none of them read.
   */
  private static UUID causeOf(EventFrame frame) {
    if (frame.id() == null) {
      return null;
    }
    try {
      return UUID.fromString(frame.id());
    } catch (IllegalArgumentException notAUuid) {
      LOG.debugf("frame id %s is not a uuid; dispatching %s with no cause", frame.id(), frame.name());
      return null;
    }
  }

  /**
   * Name the frame that could not be read, if anything about it can still be named.
   *
   * <p>Deliberately a <em>second, untyped</em> read rather than a substring of the text: a frame
   * that is well-formed JSON but will not bind to {@link EventFrame} — precisely what a missing
   * native-image registration looks like, since {@code readTree} needs no reflection while binding
   * to a record does — still yields its {@code name} and {@code id}. That is the whole difference
   * between "qits-events sent something this version does not understand" and "this build cannot
   * read its own contract", and it is knowable at the moment of the failure or not at all.
   *
   * <p>The frame's own text is never logged. It is another service's data of unbounded size, and
   * the two identifiers are what a person needs to go look the event up in the event log.
   */
  private static String describe(String text) {
    try {
      JsonNode node = CanonicalJson.parse(text);
      JsonNode name = node.get("name");
      JsonNode id = node.get("id");
      if (name != null || id != null) {
        return (name == null ? "?" : name.asText()) + " " + (id == null ? "?" : id.asText());
      }
    } catch (RuntimeException e) {
      // Not JSON at all. There is no identity to report, and the text is not ours to put in a log.
    }
    return "unidentifiable";
  }
}
