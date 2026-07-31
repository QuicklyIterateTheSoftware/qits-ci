package eu.wohlben.qits.eventsourcing.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.QitsEvent;
import eu.wohlben.qits.eventsourcing.QitsEventListener;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The registry of {@link QitsEventListener} beans and the routing of one arriving frame to them.
 *
 * <p>Two jobs that are really one: the set of signatures to subscribe to and the map used to
 * dispatch are the <em>same</em> derivation from the same beans, so a listener cannot be subscribed
 * for and then not delivered to, or vice versa. That symmetry is the reason this is not two
 * classes.
 *
 * <p><b>Not CDI events.</b> Delivery is a direct call on the listener bean. Routing remote arrivals
 * through {@code @Observes} would make them indistinguishable from locally fired events of the same
 * type at every observer site — the plan's {@code @FromEventBus} qualifier exists to fix that if
 * CDI delivery were ever wanted, and it is not needed while the interface is the contract.
 *
 * <p>Resolved once, at first use, and not re-read: listener beans are a build-time fact and the
 * subscription frame sent at connect has to agree with what dispatch will do for the life of the
 * connection.
 */
@ApplicationScoped
public class EventDispatcher {

  private static final Logger LOG = Logger.getLogger(EventDispatcher.class);

  @Inject @Any Instance<QitsEventListener<?>> listenerBeans;

  /** Signature → the listeners wanting it. Sorted, so the subscribe frame is stable across boots. */
  private final Map<String, List<QitsEventListener<?>>> bySignature = new TreeMap<>();

  @PostConstruct
  void index() {
    for (QitsEventListener<?> listener : listenerBeans) {
      // The signature is derived from the CLASS, since there is no instance to ask — which is the
      // constraint QitsEvent#signature()'s javadoc warns about from the other side.
      String signature = listener.eventType().getSimpleName();
      bySignature.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(listener);
    }
    if (!bySignature.isEmpty()) {
      LOG.debugf("event listeners registered for %s", bySignature.keySet());
    }
  }

  /**
   * What to put in the subscribe frame, in a stable order. Empty means there is no reason to open a
   * stream at all.
   */
  public List<String> signatures() {
    return List.copyOf(bySignature.keySet());
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
   * is running because of the same arrival, so they see the same cause. The scope is unwound before
   * this method returns — including when a listener throws, since {@code with} restores in a {@code
   * finally} — which is what keeps a stale parent off the next frame on a worker thread that is
   * reused for the life of the process.
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
    if (listeners == null) {
      // Ordinary: the server may broadcast more than was asked for, and a subscription set is a
      // filter rather than a promise.
      LOG.debugf("no listener for signature %s", frame.name());
      return;
    }
    CausationScope.with(causeOf(frame), () -> deliver(frame, listeners));
  }

  @SuppressWarnings("unchecked")
  private void deliver(EventFrame frame, List<QitsEventListener<?>> listeners) {
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
