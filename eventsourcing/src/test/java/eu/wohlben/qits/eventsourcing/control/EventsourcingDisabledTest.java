package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.eventsourcing.QitsEventBus;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Dark means dark. {@code qits.eventsourcing.enabled=false} is the posture every deployable takes
 * in {@code %dev} and {@code %test}, and the reason it is worth a test of its own is that "disabled"
 * has three separate ways to leak: a publish that still dials, a sweeper that still drains, and a
 * subscriber that still holds a socket open against a service nobody started.
 *
 * <p>The stub is here <b>so that a dial would be visible</b>. Asserting "no exception was thrown"
 * would pass against every one of those leaks; asserting that a real listening server saw nothing
 * at all does not.
 */
@QuarkusTest
@TestProfile(EventsourcingDisabledTest.Disabled.class)
@WithTestResource(StubEventsServer.class)
class EventsourcingDisabledTest extends EventsourcingTestSupport {

  public static class Disabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.eventsourcing.enabled", "false");
    }
  }

  @Inject QitsEventBus bus;
  @Inject OutboxSweeper sweeper;
  @Inject EventStreamSubscriber subscriber;

  @Test
  void publishingIsANoOpThatReachesNothingAndRecordsNothing() {
    bus.publish(new ThingHappened("shipped", 1, T0));

    assertEquals(0, StubEventsServer.puts().size(), "no request may leave a disabled module");
    assertEquals(0, outboxCount(), "and nothing is queued for later either");
  }

  @Test
  void theSweeperDoesNothing() {
    assertEquals(0, sweeper.sweep());
  }

  @Test
  void theSubscriberNeverDialled() {
    assertFalse(subscriber.connected());
    assertEquals(0, StubEventsServer.openStreams());
  }
}
