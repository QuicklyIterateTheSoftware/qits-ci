package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.QitsEventBus;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
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

  /**
   * Causation changes nothing about darkness, in either of its two shapes: a scope establishes a
   * value on a thread whether or not anything is listening, and an explicit parent is just an
   * argument. Neither may become a reason for a disabled bus to dial, and neither may become a row.
   */
  @Test
  void aDisabledBusStampsNothingBecauseItPublishesNothing() {
    CausationScope.with(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        () -> bus.publish(new ThingHappened("shipped", 1, T0)));
    bus.publish(
        new ThingHappened("shipped", 2, T0),
        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));

    assertEquals(0, StubEventsServer.puts().size(), "a cause is not a reason to dial");
    assertEquals(0, outboxCount());
    assertNull(CausationScope.current(), "and the scope still unwound");
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
