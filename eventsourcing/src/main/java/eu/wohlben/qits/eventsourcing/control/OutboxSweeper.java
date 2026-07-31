package eu.wohlben.qits.eventsourcing.control;

import eu.wohlben.qits.eventsourcing.entity.OutboxEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Re-attempts what the inline publish could not deliver.
 *
 * <p>Runs on the scheduler's own thread, never on a caller's, so a qits-events that is down costs
 * the publishing service nothing after the first bounded attempt. {@code ConcurrentExecution.SKIP}
 * matters more than it looks: a tick that is still working through a backlog when the next one
 * fires must not have a second thread re-attempting the same rows, because two in-flight {@code
 * PUT}s of one id is the one situation the server's idempotency was not designed to arbitrate.
 *
 * <p>{@link #sweep()} is public and returns what it did, which is how the retry schedule is tested:
 * a test moves its clock to the row's {@code nextAttemptAt} and calls this, instead of sleeping
 * through eighty seconds of real backoff.
 */
@ApplicationScoped
public class OutboxSweeper {

  private static final Logger LOG = Logger.getLogger(OutboxSweeper.class);

  @Inject Outbox outbox;

  @Inject EventsPublisher publisher;

  @Inject Clock clock;

  @ConfigProperty(name = "qits.eventsourcing.enabled")
  boolean enabled;

  /**
   * The tick. The cadence is a floor on how late a retry can be, not the retry spacing itself —
   * that is {@link RetrySchedule}, held per row in {@code nextAttemptAt}, so a short cadence costs
   * one query and never sends anything early.
   */
  @Scheduled(
      every = "{qits.eventsourcing.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    sweep();
  }

  /** Attempt every row that is due now. Returns how many were attempted. */
  public int sweep() {
    if (!enabled) {
      return 0;
    }
    Instant now = clock.instant();
    List<OutboxEvent> due = outbox.due(now);
    for (OutboxEvent row : due) {
      EventsPublisher.Delivery attempt =
          publisher.put(
              row.id, new EventEnvelope(row.name, row.occurredAt, row.payload, row.description));
      if (attempt.delivered()) {
        outbox.delivered(row.id);
      } else {
        outbox.attemptFailed(row.id, attempt);
      }
    }
    if (!due.isEmpty()) {
      LOG.debugf("outbox sweep attempted %d row(s)", due.size());
    }
    return due.size();
  }
}
