package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventsourcing.QitsEventBus;
import eu.wohlben.qits.eventsourcing.control.TestEvents.ThingHappened;
import eu.wohlben.qits.eventsourcing.entity.OutboxEvent;
import eu.wohlben.qits.eventsourcing.entity.OutboxStatus;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The durability contract: what {@code publish()} does when the {@code PUT} lands, and what happens
 * to the event when it does not.
 *
 * <p>Driven against {@link StubEventsServer} — a real socket, a real request, a scripted answer —
 * because the thing under test is a decision made from an HTTP status code and there is no honest
 * way to reach that without one. The clock is the {@link TestClock}, so the eighty-odd seconds the
 * retry schedule really spans are walked in a few milliseconds, and the sweeper is called rather
 * than waited for.
 */
@QuarkusTest
@WithTestResource(StubEventsServer.class)
class OutboxFlowTest extends EventsourcingTestSupport {

  @Inject QitsEventBus bus;
  @Inject OutboxSweeper sweeper;

  @Test
  void aPublishThatLandsWritesNothingLocally() {
    StubEventsServer.answerWith(201);

    ThingHappened event = new ThingHappened("shipped", 1, T0);
    bus.publish(event);

    assertEquals(1, StubEventsServer.puts().size());
    assertEquals(event.eventId().toString(), StubEventsServer.puts().get(0).id());
    assertEquals(0, outboxCount(), "a delivered event must leave no row behind");
  }

  @Test
  void aReplayIsASuccessAndNotAConflict() {
    // 200 means "already here, identical" — the answer a PUT whose response was lost gets on its
    // way back through. It is a delivery.
    StubEventsServer.answerWith(200);

    bus.publish(new ThingHappened("shipped", 1, T0));

    assertEquals(0, outboxCount());
  }

  @Test
  void thePutCarriesTheEnvelopeAtTheContractsAddress() {
    StubEventsServer.answerWith(201);
    ThingHappened event = new ThingHappened("shipped", 1, T0);

    bus.publish(event);

    StubEventsServer.Put put = StubEventsServer.puts().get(0);
    assertEquals(event.eventId().toString(), put.id());
    assertEquals(CanonicalJson.envelope(EventEnvelope.of(event)), put.body());
  }

  @Test
  void aRetryableFailureBecomesAPendingRowDueOneSecondLater() {
    StubEventsServer.answerWith(503);
    ThingHappened event = new ThingHappened("shipped", 1, T0);

    bus.publish(event);

    OutboxEvent row = rowFor(event);
    assertEquals(OutboxStatus.PENDING, row.status);
    assertEquals(1, row.attempts, "the inline attempt counts");
    assertEquals(T0.plusSeconds(1), row.nextAttemptAt);
    assertEquals("ThingHappened", row.name);
    assertEquals(T0, row.occurredAt);
    assertEquals(CanonicalJson.payload(event), row.payload);
    assertNull(row.description);
    assertNotNull(row.lastError);
  }

  @Test
  void theSweeperLeavesARowAloneUntilItIsDue() {
    StubEventsServer.answerWith(503);
    bus.publish(new ThingHappened("shipped", 1, T0));

    clock.advance(Duration.ofMillis(999));
    assertEquals(0, sweeper.sweep(), "not due yet");
    assertEquals(1, StubEventsServer.puts().size(), "only the inline attempt so far");
  }

  /**
   * The whole schedule in one test, because the spacing is the property: five attempts (the inline
   * one plus four sweeps) at 1s, 4s, 16s and 64s after their predecessor, and the fifth failure is
   * terminal.
   */
  @Test
  void fiveFailuresWalkTheBackoffAndThenGiveUp() {
    StubEventsServer.answerWith(503);
    ThingHappened event = new ThingHappened("shipped", 1, T0);
    bus.publish(event);

    List<Long> spacing = List.of(1L, 4L, 16L, 64L);
    for (int attempt = 1; attempt <= 4; attempt++) {
      OutboxEvent before = rowFor(event);
      assertEquals(OutboxStatus.PENDING, before.status, "attempt " + attempt);
      assertEquals(attempt, before.attempts);
      assertEquals(
          clock.instant().plusSeconds(spacing.get(attempt - 1)),
          before.nextAttemptAt,
          "spacing after attempt " + attempt);

      clock.jumpTo(before.nextAttemptAt);
      assertEquals(1, sweeper.sweep());
    }

    OutboxEvent exhausted = rowFor(event);
    assertEquals(5, exhausted.attempts);
    assertEquals(OutboxStatus.FAILED, exhausted.status);
    assertNull(exhausted.nextAttemptAt);
    assertEquals(5, StubEventsServer.puts().size());

    // And it stays given up on: a FAILED row is not due, ever.
    clock.advance(Duration.ofDays(1));
    assertEquals(0, sweeper.sweep());
  }

  @Test
  void everyAttemptCarriesTheSameEventIdAndTheSameBytes() {
    StubEventsServer.answerWith(503);
    ThingHappened event = new ThingHappened("shipped", 1, T0);
    bus.publish(event);

    for (int attempt = 1; attempt <= 4; attempt++) {
      clock.jumpTo(rowFor(event).nextAttemptAt);
      sweeper.sweep();
    }

    List<StubEventsServer.Put> puts = StubEventsServer.puts();
    assertEquals(5, puts.size());
    String expectedId = event.eventId().toString();
    String expectedBody = CanonicalJson.envelope(EventEnvelope.of(event));
    for (StubEventsServer.Put put : puts) {
      // The stability of the id is what makes the retry a replay rather than a duplicate, and the
      // stability of the body is what makes qits-events answer 200 to it rather than 400.
      assertEquals(expectedId, put.id());
      assertEquals(expectedBody, put.body());
    }
  }

  @Test
  void aRetryThatLandsRemovesTheRow() {
    // Fail once inline, then answer the first sweep with the replay 200.
    StubEventsServer.answerWith(503, 200);
    ThingHappened event = new ThingHappened("shipped", 1, T0);
    bus.publish(event);

    clock.jumpTo(rowFor(event).nextAttemptAt);
    assertEquals(1, sweeper.sweep());

    assertEquals(0, outboxCount(), "a delivered event is not kept");
    assertEquals(2, StubEventsServer.puts().size());
  }

  @Test
  void a400InlineIsFailedAtOnceAndNeverRetried() {
    StubEventsServer.answerWith(400);
    ThingHappened event = new ThingHappened("shipped", 1, T0);

    bus.publish(event);

    OutboxEvent row = rowFor(event);
    assertEquals(OutboxStatus.FAILED, row.status);
    assertEquals(1, row.attempts, "one attempt, and no budget spent pretending it could work");
    assertNull(row.nextAttemptAt);
    assertTrue(row.lastError.startsWith("400"), row.lastError);

    clock.advance(Duration.ofDays(1));
    assertEquals(0, sweeper.sweep());
    assertEquals(1, StubEventsServer.puts().size());
  }

  @Test
  void a400FromTheSweeperIsAlsoTerminalWithTheBudgetUnspent() {
    StubEventsServer.answerWith(503, 400);
    ThingHappened event = new ThingHappened("shipped", 1, T0);
    bus.publish(event);

    clock.jumpTo(rowFor(event).nextAttemptAt);
    sweeper.sweep();

    OutboxEvent row = rowFor(event);
    assertEquals(OutboxStatus.FAILED, row.status);
    assertEquals(2, row.attempts, "terminal on the answer, not on the budget");
    assertNull(row.nextAttemptAt);
  }

  private OutboxEvent rowFor(ThingHappened event) {
    OutboxEvent row = row(event.eventId().toString());
    assertNotNull(row, "expected an outbox row for " + event.eventId());
    return row;
  }
}
