package eu.wohlben.qits.eventsourcing.control;

import eu.wohlben.qits.eventsourcing.entity.OutboxEvent;
import eu.wohlben.qits.eventsourcing.entity.OutboxStatus;
import eu.wohlben.qits.eventsourcing.persistence.OutboxRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The outbox's state transitions, and the only place they happen.
 *
 * <p><b>Every write is its own transaction.</b> {@link #enqueue} is called from inside whatever the
 * publishing service was doing — for qits-ci, from the SUCCESS transition — and an outbox row that
 * vanished because the caller's transaction rolled back afterwards would be a durability guarantee
 * that holds only when nothing goes wrong. {@code requiringNew} suspends the caller's transaction
 * and commits this row on its own, which is the entire reason the mechanism is worth having.
 *
 * <p>A delivered row is <b>deleted</b> rather than marked. The outbox is what has not arrived; the
 * record of what has is qits-events, which is the point of publishing to it.
 */
@ApplicationScoped
public class Outbox {

  private static final Logger LOG = Logger.getLogger(Outbox.class);

  /** One sweep's bite. Large enough that a real backlog drains, small enough to bound one tick. */
  static final int SWEEP_BATCH = 100;

  @Inject OutboxRepository rows;

  @Inject Clock clock;

  @ConfigProperty(name = "qits.eventsourcing.max-attempts", defaultValue = "5")
  int maxAttempts;

  /**
   * Record an event the inline {@code PUT} did not deliver. The attempt just made counts, so the
   * row starts at one and the sweeper's first try is the second attempt.
   */
  public void enqueue(String eventId, EventEnvelope envelope, EventsPublisher.Delivery attempt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OutboxEvent row = new OutboxEvent();
              row.id = eventId;
              row.name = envelope.name();
              row.occurredAt = envelope.occurredAt();
              row.payload = envelope.payload();
              row.description = envelope.description();
              // The envelope is stored WHOLE, and the parent is part of it: it participates in
              // qits-events' idempotency comparison, so a retry that lost it would contradict the
              // attempt it is retrying.
              row.parentId = envelope.parentId();
              row.attempts = 1;
              apply(row, attempt);
              rows.persist(row);
            });
  }

  /** The rows the sweeper may attempt at {@code now}. */
  public List<OutboxEvent> due(Instant now) {
    return QuarkusTransaction.requiringNew().call(() -> rows.due(now, SWEEP_BATCH));
  }

  /** It arrived. Nothing left to keep. */
  public void delivered(String eventId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              rows.deleteById(eventId);
              LOG.debugf("outbox row %s delivered on retry", eventId);
            });
  }

  /** It did not arrive. Count the attempt and decide whether there is another one. */
  public void attemptFailed(String eventId, EventsPublisher.Delivery attempt) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              OutboxEvent row = rows.findById(eventId);
              if (row == null) {
                return;
              }
              row.attempts++;
              apply(row, attempt);
            });
  }

  /**
   * The one decision this class makes, shared by the inline path and the sweeper so the two cannot
   * drift: a rejection is terminal at once, an exhausted budget is terminal, and anything else gets
   * a next time at {@link RetrySchedule}'s spacing.
   */
  private void apply(OutboxEvent row, EventsPublisher.Delivery attempt) {
    row.lastError = truncate(attempt.detail());
    if (!attempt.retryable()) {
      row.status = OutboxStatus.FAILED;
      row.nextAttemptAt = null;
      LOG.warnf("event %s (%s) is unretryable after %d attempts: %s", row.id, row.name, row.attempts, row.lastError);
      return;
    }
    if (row.attempts >= maxAttempts) {
      row.status = OutboxStatus.FAILED;
      row.nextAttemptAt = null;
      LOG.warnf("event %s (%s) gave up after %d attempts: %s", row.id, row.name, row.attempts, row.lastError);
      return;
    }
    row.status = OutboxStatus.PENDING;
    row.nextAttemptAt = clock.instant().plus(RetrySchedule.outboxBackoff(row.attempts));
    LOG.debugf(
        "event %s (%s) attempt %d failed, next at %s: %s",
        row.id, row.name, row.attempts, row.nextAttemptAt, row.lastError);
  }

  private static String truncate(String detail) {
    if (detail == null) {
      return null;
    }
    return detail.length() > 1024 ? detail.substring(0, 1024) : detail;
  }
}
