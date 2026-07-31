package eu.wohlben.qits.eventsourcing.control;

import eu.wohlben.qits.eventsourcing.entity.OutboxEvent;
import eu.wohlben.qits.eventsourcing.persistence.OutboxRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for the eventsourcing {@code @QuarkusTest}s: empties the outbox outside the test's own
 * transaction, forgets what the stub saw, and puts the clock back at a known instant — so every
 * test starts from the same slate (the {@code CiTestSupport} pattern one module over).
 */
public abstract class EventsourcingTestSupport {

  /** Where every test's clock starts. Fixed, so an assertion can name an instant rather than a delta. */
  protected static final Instant T0 = Instant.parse("2026-07-31T12:00:00Z");

  @Inject protected OutboxRepository outboxRows;
  @Inject protected TestClock clock;

  @BeforeEach
  void resetEventsourcingState() {
    QuarkusTransaction.requiringNew().run(outboxRows::deleteAll);
    StubEventsServer.reset();
    clock.set(T0);
  }

  /**
   * Read a row back the way the sweeper wrote it: in its own transaction, so the read really goes
   * to the database rather than to whatever this thread's persistence context still remembers from
   * before {@code Outbox}'s own {@code requiringNew} committed.
   */
  protected OutboxEvent row(String eventId) {
    return QuarkusTransaction.requiringNew().call(() -> outboxRows.findById(eventId));
  }

  /** How many events are still undelivered. Zero is the healthy number. */
  protected long outboxCount() {
    return QuarkusTransaction.requiringNew().call(() -> outboxRows.count());
  }
}
