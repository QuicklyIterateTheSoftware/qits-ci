package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.QitsEventBus;
import eu.wohlben.qits.eventsourcing.control.TestEvents.CausationProbeListener;
import eu.wohlben.qits.eventsourcing.control.TestEvents.OtherThingHappened;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingListener;
import eu.wohlben.qits.eventsourcing.entity.OutboxEvent;
import eu.wohlben.qits.eventsourcing.entity.OutboxStatus;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Causation, end to end on this side: what the bus stamps, what the dispatcher establishes, and what
 * the outbox keeps.
 *
 * <p>Asserted on the <b>recorded PUT bodies</b> rather than on an envelope built in the test,
 * because the wire is the contract — qits-events compares {@code name} + {@code occurredAt} +
 * {@code payload} + {@code parentId} and a value that never left this JVM proves nothing about any
 * of that. {@link StubEventsServer} keeps every request verbatim, which is exactly what is needed.
 */
@QuarkusTest
@WithTestResource(StubEventsServer.class)
class CausationStampingTest extends EventsourcingTestSupport {

  private static final UUID SCOPE_CAUSE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID ARGUMENT_CAUSE = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID UNRELATED_CAUSE = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");

  @Inject QitsEventBus bus;
  @Inject OutboxSweeper sweeper;
  @Inject EventDispatcher dispatcher;
  @Inject ThingListener things;
  @Inject CausationProbeListener probe;

  @BeforeEach
  void resetListeners() {
    things.reset();
    probe.reset();
  }

  /**
   * The probe is a bean of the shared test application, so an arming that outlived this class would
   * reach every other suite in the JVM. Disarmed on the way out as well as on the way in.
   */
  @AfterEach
  void disarmTheProbe() {
    probe.reset();
    assertNull(CausationScope.current(), "no test here may leave a cause on the JUnit thread");
  }

  // -- the precedence rule, all four combinations ------------------------------------------------

  @Test
  void publishInsideAScopeStampsTheScopesCause() {
    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(new ThingHappened("shipped", 1, T0)));

    assertEquals(SCOPE_CAUSE.toString(), parentOfOnlyPut());
  }

  /** An explicit argument always wins: the author knows something the runtime does not. */
  @Test
  void anExplicitParentBeatsTheAmbientOne() {
    CausationScope.with(
        SCOPE_CAUSE, () -> bus.publish(new ThingHappened("shipped", 1, T0), ARGUMENT_CAUSE));

    assertEquals(ARGUMENT_CAUSE.toString(), parentOfOnlyPut());
  }

  @Test
  void publishOutsideAnyScopeStampsNothing() {
    bus.publish(new ThingHappened("shipped", 1, T0));

    assertNull(parentOfOnlyPut(), "a root event, and an explicit null on the wire");
  }

  /**
   * The settled asymmetry: {@code publish(event, null)} means "I have no argument", not "detach". It
   * is exactly {@code publish(event)} — one implementation, one call shape — and the detach is
   * spelled {@code CausationScope.with(null, …)}.
   */
  @Test
  void publishWithAnExplicitNullFallsBackToTheAmbientCause() {
    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(new ThingHappened("shipped", 1, T0), null));

    assertEquals(SCOPE_CAUSE.toString(), parentOfOnlyPut());
  }

  @Test
  void aDetachedRegionPublishesARootEvenInsideAScope() {
    CausationScope.with(
        SCOPE_CAUSE,
        () -> CausationScope.with(null, () -> bus.publish(new ThingHappened("shipped", 1, T0))));

    assertNull(parentOfOnlyPut());
  }

  // -- the wire shape ----------------------------------------------------------------------------

  @Test
  void parentIdIsAnExplicitNullWhenThereIsNoneAndTheStringWhenThereIs() {
    bus.publish(new ThingHappened("shipped", 1, T0));
    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(new ThingHappened("again", 2, T0)));

    JsonNode root = CanonicalJson.parse(StubEventsServer.puts().get(0).body());
    assertTrue(root.has("parentId"), "the key is always present: " + root);
    assertTrue(root.get("parentId").isNull(), "and it is an explicit null, like description");
    assertEquals(
        List.of("description", "name", "occurredAt", "parentId", "payload"),
        root.properties().stream().map(Map.Entry::getKey).toList(),
        "alphabetical, so the new key lands between occurredAt and payload with no ordering rule");

    JsonNode caused = CanonicalJson.parse(StubEventsServer.puts().get(1).body());
    assertEquals(SCOPE_CAUSE.toString(), caused.get("parentId").asText());
  }

  /**
   * <b>Causation must not reach the compared bytes.</b> The payload is what qits-events stores
   * verbatim and diffs to tell a replay from a reused id, so a parent that leaked into it would make
   * one event published under two causes into two events nothing could reconcile. This is the same
   * lesson the mix-in taught about {@code eventId}, asserted before it can be relearned.
   */
  @Test
  void theCanonicalPayloadIsIdenticalWithAParentAndWithout() {
    bus.publish(new ThingHappened("shipped", 1, T0));
    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(new ThingHappened("shipped", 1, T0)));

    List<StubEventsServer.Put> puts = StubEventsServer.puts();
    assertEquals(2, puts.size());
    assertNotEquals(puts.get(0).id(), puts.get(1).id(), "two occurrences, two ids");

    String rootPayload = CanonicalJson.parse(puts.get(0).body()).get("payload").asText();
    String causedPayload = CanonicalJson.parse(puts.get(1).body()).get("payload").asText();
    assertEquals(rootPayload, causedPayload, "the parent is envelope and never payload");
    assertTrue(rootPayload.contains("\"what\":\"shipped\""), rootPayload);
    assertFalse(rootPayload.contains("parentId"), "no causation in the payload: " + rootPayload);
  }

  // -- the dispatcher ----------------------------------------------------------------------------

  /**
   * The mechanism's whole reason to exist: a listener publishes while consuming, names no parent,
   * and the event that arrives on the wire carries the id of the frame that woke it.
   */
  @Test
  void anEventPublishedDuringConsumptionIsStampedWithTheArrivingFramesId() {
    probe.publishWhileConsuming();
    String arriving = UUID.randomUUID().toString();

    dispatcher.dispatch(frame(arriving, "ThingHappened", new ThingHappened("shipped", 1, T0)));

    List<StubEventsServer.Put> puts = StubEventsServer.puts();
    assertEquals(1, puts.size(), "the listener's follow-up, and nothing else");
    OtherThingHappened followUp = probe.published();
    assertNotNull(followUp);
    assertEquals(followUp.eventId().toString(), puts.get(0).id());
    assertEquals(arriving, CanonicalJson.parse(puts.get(0).body()).get("parentId").asText());
  }

  @Test
  void bothListenersOnOneFrameSeeTheSameCause() {
    String arriving = UUID.randomUUID().toString();

    dispatcher.dispatch(frame(arriving, "ThingHappened", new ThingHappened("shipped", 1, T0)));

    assertEquals(List.of(Optional.of(UUID.fromString(arriving))), things.causes());
    assertEquals(things.causes(), probe.causes(), "one arrival, one cause, however many listeners");
  }

  @Test
  void theCauseIsGoneAgainOnceDispatchReturns() {
    dispatcher.dispatch(
        frame(UUID.randomUUID().toString(), "ThingHappened", new ThingHappened("shipped", 1, T0)));

    assertNull(CausationScope.current(), "the dispatch thread is left as it was found");
  }

  /**
   * A listener that throws is logged and swallowed — and its scope is unwound anyway, so the next
   * frame on the same worker starts clean. The failure being caught inside the loop is what makes
   * the second listener still run under the same cause.
   */
  @Test
  void aListenerThatThrowsLeaksNothingToTheNextFrame() {
    probe.failWhileConsuming();
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();

    dispatcher.dispatch(frame(first, "ThingHappened", new ThingHappened("one", 1, T0)));
    assertNull(CausationScope.current());

    probe.reset();
    dispatcher.dispatch(frame(second, "ThingHappened", new ThingHappened("two", 2, T0)));

    assertEquals(
        List.of(Optional.of(UUID.fromString(first)), Optional.of(UUID.fromString(second))),
        things.causes(),
        "each frame under its own cause, and the thrower did not poison the second");
  }

  /**
   * The frame's {@code id} is a string on a column with no format promise. An id that is not a UUID
   * is dispatched with no cause rather than dropped: losing an event over a field no listener reads
   * would be a worse failure than the one it guards against.
   */
  @Test
  void aFrameWhoseIdIsNotAUuidStillDispatchesWithNoCause() {
    dispatcher.dispatch(frame("not-a-uuid", "ThingHappened", new ThingHappened("shipped", 1, T0)));

    assertEquals(1, things.received().size(), "the frame is delivered");
    assertEquals(List.of(Optional.empty()), things.causes());
  }

  // -- the outbox --------------------------------------------------------------------------------

  @Test
  void aPublishThatFailsInlineWritesTheParentOnTheRow() {
    StubEventsServer.answerWith(503);
    ThingHappened event = new ThingHappened("shipped", 1, T0);

    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(event));

    OutboxEvent row = rowFor(event);
    assertEquals(OutboxStatus.PENDING, row.status);
    assertEquals(SCOPE_CAUSE.toString(), row.parentId);
  }

  /**
   * <b>The regression this whole column exists to prevent.</b> The sweeper re-sends a stored
   * envelope, and {@code parentId} is part of qits-events' idempotency comparison — so a retry that
   * lost it would 400 against a first attempt that had in fact landed, or republish a caused event
   * as a chain root. The sweep here runs inside a <em>different</em> scope on purpose: the value
   * that goes out must come from the row and from nothing ambient.
   */
  @Test
  void aSweptRetryCarriesTheOriginalParentAndNotTheSweepersContext() {
    StubEventsServer.answerWith(503, 200);
    ThingHappened event = new ThingHappened("shipped", 1, T0);
    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(event));

    clock.jumpTo(rowFor(event).nextAttemptAt);
    CausationScope.with(UNRELATED_CAUSE, () -> assertEquals(1, sweeper.sweep()));

    List<StubEventsServer.Put> puts = StubEventsServer.puts();
    assertEquals(2, puts.size());
    assertEquals(puts.get(0).body(), puts.get(1).body(), "a retry is the same request, byte for byte");
    assertEquals(SCOPE_CAUSE.toString(), CanonicalJson.parse(puts.get(1).body()).get("parentId").asText());
    assertEquals(0, outboxCount(), "and the 200 removed the row");
  }

  @Test
  void aFailedRowKeepsItsParent() {
    StubEventsServer.answerWith(400);
    ThingHappened event = new ThingHappened("shipped", 1, T0);

    CausationScope.with(SCOPE_CAUSE, () -> bus.publish(event));

    OutboxEvent row = rowFor(event);
    assertEquals(OutboxStatus.FAILED, row.status);
    assertEquals(
        SCOPE_CAUSE.toString(),
        row.parentId,
        "a dead-letter row that lost its cause could never be resent as what it was");
  }

  @Test
  void aRootEventsRowCarriesANullParent() {
    StubEventsServer.answerWith(503);
    ThingHappened event = new ThingHappened("shipped", 1, T0);

    bus.publish(event);

    assertNull(rowFor(event).parentId);
  }

  // -- helpers -----------------------------------------------------------------------------------

  /** The {@code parentId} of the single PUT that must have arrived, as a string or null. */
  private static String parentOfOnlyPut() {
    List<StubEventsServer.Put> puts = StubEventsServer.puts();
    assertEquals(1, puts.size(), "expected exactly one PUT: " + puts);
    JsonNode parent = CanonicalJson.parse(puts.get(0).body()).get("parentId");
    assertNotNull(parent, "parentId is always on the wire, even as a null");
    return parent.isNull() ? null : parent.asText();
  }

  /** The shape qits-events pushes, with the id a test wants to see stamped on what follows. */
  private static String frame(String id, String name, ThingHappened event) {
    return CanonicalJson.canonicalize(
        new EventFrame(id, name, event.at(), CanonicalJson.payload(event), null, null));
  }

  private OutboxEvent rowFor(ThingHappened event) {
    OutboxEvent row = row(event.eventId().toString());
    assertNotNull(row, "expected an outbox row for " + event.eventId());
    return row;
  }
}
