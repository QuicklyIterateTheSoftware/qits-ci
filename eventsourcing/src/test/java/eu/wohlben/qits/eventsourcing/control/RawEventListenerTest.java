package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventsourcing.CausationScope;
import eu.wohlben.qits.eventsourcing.control.TestEvents.CausationProbeListener;
import eu.wohlben.qits.eventsourcing.control.TestEvents.RecordingRawListener;
import eu.wohlben.qits.eventsourcing.control.TestEvents.SecondRawListener;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingListener;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The raw seam: who gets which frame, in what order relative to the typed path, under what cause,
 * and what a badly behaved raw listener can and cannot take down with it.
 *
 * <p>Dispatch is driven <b>directly</b> rather than through a broadcast, as {@code
 * CausationStampingTest} does and for the same reason: these are claims about routing, and routing a
 * frame that has already arrived is exactly the unit. The socket is used for the two cases that are
 * genuinely about the wire — what the subscribe frame says after a reconnect.
 *
 * <p>The recording listeners are beans of the shared test application, so an arming that outlived
 * this class would reach every other suite in the JVM — and one of those asserts the subscribe frame
 * literally. Disarmed on the way out as well as on the way in.
 */
@QuarkusTest
@WithTestResource(StubEventsServer.class)
class RawEventListenerTest extends EventsourcingTestSupport {

  private static final Duration PATIENCE = Duration.ofSeconds(10);

  @Inject EventDispatcher dispatcher;
  @Inject EventStreamSubscriber subscriber;
  @Inject ThingListener things;
  @Inject CausationProbeListener probe;
  @Inject RecordingRawListener raw;
  @Inject SecondRawListener secondRaw;

  @BeforeEach
  void resetListeners() {
    things.reset();
    probe.reset();
    raw.reset();
    secondRaw.reset();
    TestEvents.clearDeliveries();
    await(subscriber::connected, "the stream to be up");
  }

  @AfterEach
  void disarm() {
    raw.reset();
    secondRaw.reset();
    probe.reset();
    assertNull(CausationScope.current(), "no test here may leave a cause on the JUnit thread");
  }

  // -- routing -------------------------------------------------------------------------------------

  /**
   * The frame arrives <b>as the frame</b>, all six components. That is the whole difference between
   * this seam and the typed one, which hands over a deserialized event and keeps the envelope to
   * itself — so a consumer that needs the event's id, or its cause, or the payload unparsed has
   * somewhere to get them.
   */
  @Test
  void aRawListenerIsHandedTheWholeFrameForASignatureItAskedFor() {
    raw.wants("ThingHappened");
    String id = UUID.randomUUID().toString();
    String previousHop = UUID.randomUUID().toString();
    ThingHappened event = new ThingHappened("shipped", 7, T0);

    dispatcher.dispatch(
        frame(id, "ThingHappened", CanonicalJson.payload(event), "why it happened", previousHop));

    assertEquals(1, raw.frames().size());
    EventFrame got = raw.frames().get(0);
    assertEquals(id, got.id());
    assertEquals("ThingHappened", got.name());
    assertEquals(T0, got.occurredAt());
    assertEquals(CanonicalJson.payload(event), got.payload());
    assertEquals("why it happened", got.description());
    assertEquals(previousHop, got.parentId(), "the previous hop, not the one it would publish under");
  }

  /**
   * {@code "*"} on the consuming side, which is the half the subscription cannot express for a
   * listener whose interest changes: everything arrives, including signatures no typed listener has
   * ever heard of, and the typed path stays filtered exactly as it was.
   */
  @Test
  void aStarListenerSeesEveryFrameIncludingOnesNoTypedListenerWants() {
    raw.wantsEverything();
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();

    dispatcher.dispatch(
        frame(first, "ThingHappened", CanonicalJson.payload(new ThingHappened("shipped", 1, T0))));
    dispatcher.dispatch(frame(second, "SomethingElseEntirely", "{\"anything\":true}"));

    assertEquals(
        List.of("ThingHappened", "SomethingElseEntirely"),
        raw.frames().stream().map(EventFrame::name).toList());
    assertEquals(1, things.received().size(), "the typed path is filtered as it always was");
    assertEquals(
        List.of(Optional.of(UUID.fromString(first)), Optional.of(UUID.fromString(second))),
        raw.causes(),
        "a frame only the raw seam wanted is still scoped to its own id");
  }

  @Test
  void aSignatureARawListenerDidNotAskForDoesNotReachIt() {
    raw.wants("OtherThingHappened");

    dispatcher.dispatch(
        frame(
            UUID.randomUUID().toString(),
            "ThingHappened",
            CanonicalJson.payload(new ThingHappened("shipped", 1, T0))));

    assertEquals(List.of(), raw.frames(), "a raw set is a filter, not a subscription to everything");
    assertEquals(1, things.received().size());
  }

  /**
   * <b>Coexistence, and the stated order.</b> One frame, two typed listeners and one raw listener
   * that all want it: three deliveries, no duplicates, and every typed one before the raw one. The
   * order is a contract — see {@code EventDispatcher}'s class javadoc — so it is asserted as a list
   * rather than as "the raw listener also ran".
   */
  @Test
  void oneFrameReachesBothSeamsExactlyOnceWithTypedFirst() {
    raw.wants("ThingHappened");

    dispatcher.dispatch(
        frame(
            UUID.randomUUID().toString(),
            "ThingHappened",
            CanonicalJson.payload(new ThingHappened("shipped", 1, T0))));

    assertEquals(1, things.received().size(), "the typed listener, once");
    assertEquals(1, raw.frames().size(), "the raw listener, once");
    assertEquals(
        List.of("typed", "typed", "raw"),
        TestEvents.deliveries(),
        "both typed listeners on this signature, then the raw one");
  }

  // -- containment ---------------------------------------------------------------------------------

  /**
   * The same never-throw containment the typed loop has, in both directions that matter: a raw
   * listener that fails takes down neither the other raw listener nor the typed path, and the next
   * frame on the same thread is unaffected. Losing one event is the designed failure here; losing
   * the stream is not.
   */
  @Test
  void aRawListenerThatThrowsBreaksNothingElse() {
    raw.wantsEverything();
    raw.failWhileConsuming();
    secondRaw.wants("ThingHappened");

    dispatcher.dispatch(
        frame(
            UUID.randomUUID().toString(),
            "ThingHappened",
            CanonicalJson.payload(new ThingHappened("one", 1, T0))));
    dispatcher.dispatch(
        frame(
            UUID.randomUUID().toString(),
            "ThingHappened",
            CanonicalJson.payload(new ThingHappened("two", 2, T0))));

    assertEquals(2, raw.frames().size(), "the thrower itself keeps being called");
    assertEquals(2, secondRaw.frames().size(), "and so does the other raw listener");
    assertEquals(2, things.received().size(), "and the typed path never noticed");
    assertNull(CausationScope.current(), "and the scope still unwound");
  }

  /**
   * {@code signatures()} is a question dispatch asks per frame, so it is a second place a listener
   * can fail — and failing it must cost that listener its frames rather than costing the application
   * its stream. The subscribe set is still derivable afterwards, which is the half that would
   * otherwise be silent.
   */
  @Test
  void aRawListenerThatCannotSayWhatItWantsIsSkippedRatherThanFatal() {
    raw.failWhenAsked();
    secondRaw.wants("ThingHappened");

    dispatcher.dispatch(
        frame(
            UUID.randomUUID().toString(),
            "ThingHappened",
            CanonicalJson.payload(new ThingHappened("shipped", 1, T0))));

    assertEquals(List.of(), raw.frames());
    assertEquals(1, secondRaw.frames().size());
    assertEquals(1, things.received().size());
    assertEquals(
        EventStreamSubscriberTest.SUBSCRIPTION,
        dispatcher.signatures(),
        "the union survives a listener that cannot answer");
  }

  // -- causation -----------------------------------------------------------------------------------

  /**
   * The property the raw seam had to inherit rather than reinvent: a raw consumer that publishes
   * while consuming records the arriving frame as the parent, with nobody passing an argument.
   * Asserted on the recorded PUT body, because the wire is the contract.
   */
  @Test
  void anEventPublishedFromOnFrameIsStampedWithTheArrivingFramesId() {
    raw.wants("ThingHappened");
    raw.publishWhileConsuming();
    String arriving = UUID.randomUUID().toString();

    dispatcher.dispatch(
        frame(arriving, "ThingHappened", CanonicalJson.payload(new ThingHappened("shipped", 1, T0))));

    List<StubEventsServer.Put> puts = StubEventsServer.puts();
    assertEquals(1, puts.size(), "the raw listener's follow-up, and nothing else");
    assertNotNull(raw.published());
    assertEquals(raw.published().eventId().toString(), puts.get(0).id());
    assertEquals(arriving, CanonicalJson.parse(puts.get(0).body()).get("parentId").asText());
  }

  /** One arrival, one cause, however many listeners and whichever seam they came down. */
  @Test
  void bothSeamsSeeTheSameCauseOnOneFrame() {
    raw.wants("ThingHappened");
    String arriving = UUID.randomUUID().toString();

    dispatcher.dispatch(
        frame(arriving, "ThingHappened", CanonicalJson.payload(new ThingHappened("shipped", 1, T0))));

    assertEquals(List.of(Optional.of(UUID.fromString(arriving))), raw.causes());
    assertEquals(things.causes(), raw.causes());
    assertNull(CausationScope.current(), "and the dispatch thread is left as it was found");
  }

  // -- the wire ------------------------------------------------------------------------------------

  /**
   * The union, where it actually matters: on the socket, after a reconnect. The subscription set
   * lives on the connection, so this is the only moment a raw listener's contribution reaches
   * qits-events at all.
   */
  @Test
  void theResubscribeFrameCarriesTheUnionOfBothSeams() throws Exception {
    raw.wants("ThingHappened", "AlsoRawHappened");
    StubEventsServer.reset();
    StubEventsServer.dropStreams();

    String frame = StubEventsServer.awaitSubscribeFrame(PATIENCE);
    assertNotNull(frame, "expected a subscribe frame after the reconnect");
    assertEquals(
        "{\"subscribe\":[\"AlsoRawHappened\",\"OtherThingHappened\",\"QuietlyHappened\","
            + "\"RawOnlyHappened\",\"ThingHappened\"]}",
        frame);
    await(subscriber::connected, "the stream to be back up");
  }

  /**
   * <b>The collapse on the wire.</b> The trigger engine's own shape: one listener wanting everything
   * and the frame saying so in one entry, not in a list of names that would go stale on the next
   * push.
   */
  @Test
  void aStarRawListenerCollapsesTheResubscribeFrameToStar() throws Exception {
    raw.wantsEverything();
    StubEventsServer.reset();
    StubEventsServer.dropStreams();

    String frame = StubEventsServer.awaitSubscribeFrame(PATIENCE);
    assertNotNull(frame, "expected a subscribe frame after the reconnect");
    assertEquals("{\"subscribe\":[\"*\"]}", frame);
    await(subscriber::connected, "the stream to be back up");
  }

  // -- helpers -------------------------------------------------------------------------------------

  private static String frame(String id, String name, String payload) {
    return frame(id, name, payload, null, null);
  }

  /** The shape qits-events pushes: the envelope plus the row's id. */
  private static String frame(
      String id, String name, String payload, String description, String parentId) {
    return CanonicalJson.canonicalize(
        new EventFrame(id, name, T0, payload, description, parentId));
  }

  private static void await(BooleanSupplier condition, String what) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    assertTrue(condition.getAsBoolean(), "timed out waiting for " + what);
  }
}
