package eu.wohlben.qits.eventsourcing.control;

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
   */
  @SuppressWarnings("unchecked")
  public void dispatch(String text) {
    EventFrame frame;
    try {
      frame = CanonicalJson.frame(text);
    } catch (RuntimeException e) {
      LOG.debugf("dropped an unreadable frame: %s", e.getMessage());
      return;
    }
    List<QitsEventListener<?>> listeners = bySignature.get(frame.name());
    if (listeners == null) {
      // Ordinary: the server may broadcast more than was asked for, and a subscription set is a
      // filter rather than a promise.
      LOG.debugf("no listener for signature %s", frame.name());
      return;
    }
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
}
