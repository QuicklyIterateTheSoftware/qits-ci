package eu.wohlben.qits.eventsourcing.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An event that did not reach qits-events on the first try. The envelope, kept whole, plus what the
 * sweeper needs to decide when to try again and when to stop.
 *
 * <p><b>Failure-path-only persistence.</b> A publish that succeeds inline writes nothing at all —
 * this table exists for the unhappy case and is empty in a healthy process. That is the specified
 * design and it has one named hole: a crash between the inline attempt failing and this row
 * committing loses the event. Closing it is the write-ahead variant (persist first, delete on ack),
 * which is a one-method change inside {@code Outbox} and is deliberately not made.
 *
 * <p>{@link #id} is the event's own UUID, not a row key of our own. That is what makes a retry safe
 * rather than merely permitted: qits-events keys on it, so a {@code PUT} whose response was lost
 * replays as a 200 instead of writing the event twice.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent extends PanacheEntityBase {

  /** The event's UUID — the {@code {id}} of the idempotent PUT. */
  @Id public String id;

  /** The envelope's {@code name}, which is also the event's signature. */
  @Column(nullable = false)
  public String name;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /** The canonical JSON produced at publish time, stored exactly as it will be re-sent. */
  @Lob public String payload;

  /** The envelope's {@code description}. Always null today; the column exists so a row is the whole envelope. */
  @Lob public String description;

  /**
   * The envelope's {@code parentId} — the event that caused this one, or null for a root.
   *
   * <p><b>Stored rather than re-derived, and that is the whole reason this column exists.</b> The
   * cause is an ambient or argued value at the moment of the publish and is gone by the time the
   * sweeper runs, on another thread, possibly minutes later; qits-events meanwhile compares {@code
   * name} + {@code occurredAt} + {@code payload} + {@code parentId} to tell a replay from a reused
   * id. A retry that rebuilt the envelope without this would be a different request than the one it
   * is retrying — a 400 against its own landed first attempt, or a caused event quietly re-published
   * as a chain root. Same argument the {@link #payload} column carries, one field further on.
   */
  @Column(name = "parent_id", length = 36)
  public String parentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public OutboxStatus status;

  /** Delivery attempts made so far, <b>counting the inline one</b> — so a fresh row starts at 1. */
  @Column(nullable = false)
  public int attempts;

  /** When the sweeper may try again. Null once the row is {@link OutboxStatus#FAILED}. */
  @Column(name = "next_attempt_at")
  public Instant nextAttemptAt;

  /** Why the last attempt did not land, for the person reading the table. Truncated, never parsed. */
  @Column(name = "last_error", length = 1024)
  public String lastError;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  public Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
