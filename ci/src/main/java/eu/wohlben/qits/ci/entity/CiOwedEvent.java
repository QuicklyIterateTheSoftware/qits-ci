package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One domain event the trigger engine has <b>accepted</b> and not yet evaluated.
 *
 * <p>The row exists for the gap between the two, and only for it: {@code CiEventTriggerService}
 * writes it before it reports the acceptance back to the durable funnel, and deletes it the moment
 * the evaluation returns. <b>Empty in a healthy process</b>, the eventstream outbox's property and
 * for the same reason — a row that is still here is a statement about this instance, not a copy of
 * the event log.
 *
 * <p>What it buys is the half of "exactly-once effect" this consumer could not otherwise hold up.
 * The claim in {@code consumed_event} lives on the eventstream datasource and the run row lives on
 * ci's, so the two cannot share a transaction; a process that died between the claim and the
 * evaluation left the event settled with nothing to show for it. This row is what a later process
 * reads to find out. See {@code V13__owed_trigger_event.sql} for the measurement that bought it.
 *
 * <p>{@code @Uncaused} by decision, and for {@link CiReleaseAnnouncement}'s exact reason: {@link
 * #eventId} <em>is</em> the cause, so a generic causation column would be that column under a second
 * name.
 */
@Entity
@Table(name = "ci_owed_event")
@Uncaused
public class CiOwedEvent extends PanacheEntityBase {

  /** The arriving event's own id — the dedupe identity every run it records will carry. */
  @Id
  @Column(name = "event_id")
  public String eventId;

  /** The event signature, matched against a trigger file's {@code event:}. */
  @Column(name = "event_name", nullable = false)
  public String eventName;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /** The canonical JSON, verbatim: what the selection reads and what a step container is handed. */
  @Column(name = "payload", columnDefinition = "text")
  public String payload;

  /** When this instance accepted the event — what the periodic sweep's grace is measured from. */
  @Column(name = "accepted_at", nullable = false)
  public Instant acceptedAt;
}
