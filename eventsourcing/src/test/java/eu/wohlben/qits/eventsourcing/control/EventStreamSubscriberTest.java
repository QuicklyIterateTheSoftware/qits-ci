package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventsourcing.control.TestEvents.OtherThingListener;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingListener;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listening half, against a real socket: the frame that goes out at connect, the routing of
 * what comes back, and what happens when the far side goes away.
 *
 * <p>The subscriber dials on {@code StartupEvent}, so by the time a test runs the connection is
 * already up and the subscribe frame already sent — which is the honest arrangement, because "does
 * it subscribe when the application starts" is the question, not "does it subscribe when a test
 * calls a method". The stub's ephemeral port reaches the application through {@link
 * StubEventsServer}'s config override for exactly that reason: it has to be config before Quarkus
 * boots, not a parameter afterwards.
 */
@QuarkusTest
@WithTestResource(StubEventsServer.class)
class EventStreamSubscriberTest {

  private static final Duration PATIENCE = Duration.ofSeconds(10);

  @Inject EventStreamSubscriber subscriber;
  @Inject EventDispatcher dispatcher;
  @Inject ThingListener things;
  @Inject OtherThingListener otherThings;

  @BeforeEach
  void resetListeners() {
    things.reset();
    otherThings.reset();
    await(subscriber::connected, "the stream to be up");
  }

  @Test
  void theSubscriptionSetIsEveryRegisteredListenersSignature() {
    // Derived from the beans, sorted, and identical to what dispatch will actually route — one
    // derivation, so a listener cannot be subscribed for and then not delivered to. What goes on
    // the wire is asserted where it goes on the wire, in the reconnect case below.
    //
    // QuietlyHappened is the load-bearing entry: its listener is injected nowhere, which is what a
    // real consumer's listener looks like, so this line is also the assertion that ArC's
    // unused-bean removal leaves a bean reached only through Instance<QitsEventListener<?>> alone.
    assertEquals(
        List.of("OtherThingHappened", "QuietlyHappened", "ThingHappened"), dispatcher.signatures());
    assertEquals(
        "{\"subscribe\":[\"OtherThingHappened\",\"QuietlyHappened\",\"ThingHappened\"]}",
        CanonicalJson.subscribeFrame(dispatcher.signatures()));
  }

  @Test
  void aBroadcastReachesTheListenerForItsSignatureWithADeserializedPayload() {
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    StubEventsServer.broadcast(
        frame("ThingHappened", when, CanonicalJson.payload(new ThingHappened("shipped", 7, when))));

    await(() -> !things.received().isEmpty(), "the listener to be called");
    ThingHappened received = things.received().get(0);
    assertEquals("shipped", received.what());
    assertEquals(Integer.valueOf(7), received.count());
    assertEquals(when, received.at());
    assertEquals(List.of(), otherThings.received(), "the other listener must not see it");
  }

  @Test
  void aSignatureNobodyListensForIsDroppedRatherThanFatal() {
    StubEventsServer.broadcast(frame("SomethingElseEntirely", Instant.now(), "{}"));
    // …and the stream is still usable afterwards, which is the actual claim.
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    StubEventsServer.broadcast(
        frame("ThingHappened", when, CanonicalJson.payload(new ThingHappened("still here", 1, when))));

    await(() -> !things.received().isEmpty(), "the stream to survive an unknown signature");
    assertEquals("still here", things.received().get(0).what());
  }

  /**
   * Dropped, and <b>loud about it</b>. The level is the assertion: this was a DEBUG, and a native
   * image that could not bind {@link EventFrame} at all therefore consumed every frame in silence
   * for as long as it ran. A frame the subscriber asked for and cannot read is a defect signal —
   * unlike an unknown signature above, which is ordinary traffic — so it has to arrive at a level
   * that is on by default. Capturing through JUL is what makes "on by default" the thing under test:
   * the handler sees what the logger's own level lets through, so a return to DEBUG fails here.
   */
  @Test
  void anUnreadableFrameIsDroppedWithAWarningThatNamesIt() {
    List<LogRecord> captured = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            captured.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Logger dispatcherLog =
        java.util.logging.Logger.getLogger(EventDispatcher.class.getName());
    dispatcherLog.addHandler(capture);
    try {
      // Well-formed JSON that will not bind: the frame still has an identity, and reporting it is
      // the difference between "the far side sent something odd" and "this build cannot read its
      // own contract".
      String id = UUID.randomUUID().toString();
      StubEventsServer.broadcast(
          "{\"id\":\"" + id + "\",\"name\":\"ThingHappened\",\"occurredAt\":\"not a timestamp\"}");
      StubEventsServer.broadcast("not json at all");

      Instant when = Instant.parse("2026-07-31T12:46:03Z");
      StubEventsServer.broadcast(
          frame(
              "ThingHappened", when, CanonicalJson.payload(new ThingHappened("still here", 1, when))));
      await(() -> !things.received().isEmpty(), "the stream to survive a garbage frame");

      List<String> warnings =
          captured.stream()
              .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
              .map(EventStreamSubscriberTest::rendered)
              .toList();
      // Set-wise, not by index: each frame is handled on its own executor thread, so which warning
      // lands first is the pool's business and not a property worth asserting.
      assertEquals(2, warnings.size(), "both unreadable frames must be reported: " + warnings);
      assertTrue(
          warnings.stream().anyMatch(w -> w.contains("ThingHappened " + id)),
          "a frame that is JSON must be named: " + warnings);
      assertTrue(
          warnings.stream().anyMatch(w -> w.contains("unidentifiable")),
          "a frame that is not JSON has no identity to report: " + warnings);
    } finally {
      dispatcherLog.removeHandler(capture);
    }
  }

  /**
   * qits-events restarting, from this side: the socket closes and the subscriber has to come back
   * <b>and resubscribe</b> — the subscription set lives on the connection, so a reconnect that only
   * reconnected would be a stream that is up and deaf.
   */
  @Test
  void aDroppedStreamIsRedialledAndResubscribed() throws Exception {
    StubEventsServer.reset();
    StubEventsServer.dropStreams();

    String frame = StubEventsServer.awaitSubscribeFrame(PATIENCE);
    assertNotNull(frame, "expected a subscribe frame after the reconnect");
    assertEquals(
        "{\"subscribe\":[\"OtherThingHappened\",\"QuietlyHappened\",\"ThingHappened\"]}", frame);
    await(subscriber::connected, "the stream to be back up");

    // And it is a working stream, not merely an open socket.
    Instant when = Instant.parse("2026-07-31T12:46:03Z");
    StubEventsServer.broadcast(
        frame("ThingHappened", when, CanonicalJson.payload(new ThingHappened("after", 1, when))));
    await(() -> !things.received().isEmpty(), "the reconnected stream to deliver");
    assertEquals("after", things.received().get(0).what());
  }

  /**
   * A handler sees the record before anything formats it — jboss-logging's {@code warnf} carries the
   * format string and its arguments separately — so the test has to do what a console handler would.
   */
  private static String rendered(LogRecord record) {
    Object[] params = record.getParameters();
    if (params == null || params.length == 0) {
      return String.valueOf(record.getMessage());
    }
    try {
      return String.format(record.getMessage(), params);
    } catch (RuntimeException notPrintf) {
      return record.getMessage() + " " + Arrays.toString(params);
    }
  }

  /** The shape qits-events pushes: the envelope plus the row's id. */
  private static String frame(String name, Instant occurredAt, String payload) {
    return CanonicalJson.canonicalize(
        new EventFrame(UUID.randomUUID().toString(), name, occurredAt, payload, null));
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
