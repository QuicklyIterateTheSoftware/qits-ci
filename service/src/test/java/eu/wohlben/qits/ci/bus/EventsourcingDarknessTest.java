package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.eventsourcing.QitsEventListener;
import eu.wohlben.qits.eventsourcing.control.EventStreamSubscriber;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
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
 * <p><b>The listener bean survives ArC.</b> {@link BuildSuccessfulListener} is injected nowhere by
 * name — it is reached only through {@code Instance<QitsEventListener<?>>} — and unused-bean removal
 * would leave a deployment that subscribes to nothing, receives nothing and logs nothing to say so.
 * An {@code Instance} injection point counts as a use, which is why no {@code @Unremovable} is
 * needed; this is the assertion that keeps that true rather than believed.
 */
@QuarkusTest
public class EventsourcingDarknessTest {

  @ConfigProperty(name = "qits.eventsourcing.enabled")
  boolean enabled;

  @Inject EventStreamSubscriber subscriber;

  @Inject @Any Instance<QitsEventListener<?>> listeners;

  @Test
  public void theBusIsDarkOutsideADeployment() {
    assertFalse(enabled, "%test must ship qits.eventsourcing.enabled=false");
    assertFalse(subscriber.connected(), "a dark module dials nothing");
  }

  @Test
  public void theBuildSuccessfulListenerIsARegisteredBean() {
    assertTrue(
        StreamSupport.stream(listeners.spliterator(), false)
            .anyMatch(BuildSuccessfulListener.class::isInstance),
        "the listener must survive unused-bean removal, or the subscriber subscribes to nothing");
    assertEquals(BuildSuccessful.class, new BuildSuccessfulListener().eventType());
  }
}
