package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventStreamSubscriber;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Two facts about the shipped configuration that would otherwise only be discovered by their
 * consequences, and one of them silently.
 *
 * <p><b>The bus is dark in the suite.</b> Every other test class here runs on the default test
 * config, and this asserts what that means: the switch is off and no stream was dialled. The way it
 * fails without this test is not a failure — it is a suite that quietly spends a publish timeout per
 * green run against an unresolvable host and keeps redialling a websocket, which reads as slowness
 * rather than as misconfiguration. {@code BuildSuccessfulPublishTest} is the one class that opts
 * back in, and it does so against a stub.
 *
 * <p><b>The listener beans survive ArC.</b> They are reached only through {@code
 * Instance<QitsDurableEventListener>} — no name, no channel — and unused-bean removal would leave a
 * deployment that subscribes to nothing, receives nothing, sweeps nothing and says nothing about it.
 * An {@code Instance} injection point counts as a use, which is why no {@code @Unremovable} is
 * needed; this is the assertion that keeps that true rather than believed. It matters most for
 * {@code ScmPublishCommitListener}: removed, this service still serves every read and simply never
 * builds a push again.
 *
 * <p><b>And their consumer ids are asserted here because they are storage.</b> Each one keys a
 * {@code consumed_event} ledger and a {@code consumer_watermark}; changing one silently mints a
 * brand-new consumer that initializes at the head of the log and skips everything in between, and
 * reusing one hands a listener another's claims. A literal in this test is what makes either show up
 * as a red build rather than as a quiet gap in what was consumed.
 */
@QuarkusTest
public class EventstreamDarknessTest {

  @ConfigProperty(name = "qits.eventstream.enabled")
  boolean enabled;

  @Inject EventStreamSubscriber subscriber;

  @Inject @Any Instance<QitsDurableEventListener> listeners;

  @Test
  public void theBusIsDarkOutsideADeployment() {
    assertFalse(enabled, "%test must ship qits.eventstream.enabled=false");
    assertFalse(subscriber.connected(), "a dark module dials nothing");
  }

  @Test
  public void allFourDurableListenersAreRegisteredBeans() {
    Set<Class<?>> registered =
        StreamSupport.stream(listeners.spliterator(), false)
            .map(listener -> (Class<?>) ClientProxy.unwrap(listener).getClass())
            .collect(Collectors.toSet());
    assertTrue(
        registered.containsAll(
            Set.of(
                BuildSuccessfulListener.class,
                CiEventTriggerListener.class,
                DaemonReleaseListener.class,
                ScmPublishCommitListener.class)),
        "a listener removed as unused subscribes to nothing and is never swept: " + registered);
  }

  @Test
  public void theConsumerIdsAreTheOnesTheStoredWatermarksAreKeyedOn() {
    assertEquals("ci-release-train", BuildSuccessfulListener.CONSUMER_ID);
    assertEquals("ci-event-triggers", CiEventTriggerListener.CONSUMER_ID);
    assertEquals("ci-daemon-adopt", DaemonReleaseListener.CONSUMER_ID);
    assertEquals("ci-push-runs", ScmPublishCommitListener.CONSUMER_ID);
    assertEquals(
        4,
        StreamSupport.stream(listeners.spliterator(), false)
            .map(QitsDurableEventListener::consumerId)
            .distinct()
            .count(),
        "two listeners sharing an id share a watermark and each other's claims");
  }
}
