package eu.wohlben.qits.ci.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One candidate daemon version this instance adopted off a {@code SoftwareRelease} event for
 * {@code qits-ci-daemon} (ci-daemon-autoadopt-plan.md §2.2). The configured {@code
 * qits.ci.daemon-version} pin is never a row here -- see {@link CiDaemonPinVerdict}.
 *
 * <p>{@link #version} carries its own uniqueness (the migration's {@code
 * uq_ci_daemon_pin_version}): the download address is a plain {@code {version}} substitution, so
 * two rows naming the same version would be two conflicting answers to what it resolves to.
 * {@link #eventId} is the idempotency key adoption upserts on -- a redelivered event is a no-op,
 * never a second row.
 */
@Entity
@Table(name = "ci_daemon_pin")
public class CiDaemonPin extends PanacheEntityBase {

  @Id public String id;

  @Column(nullable = false, length = 64)
  public String version;

  /** Where this candidate came from. Always {@code "adopted"} today; a field for the day a second
   *  discovery mechanism exists, exactly as {@code GET /ci/api/daemon}'s own {@code source} is. */
  @Column(nullable = false, length = 32)
  public String source;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiDaemonPinVerdict verdict;

  /** The adopting {@code SoftwareRelease} event's id -- the idempotency key for adoption. */
  @Column(name = "event_id", nullable = false)
  public String eventId;

  /** The adopting event's own timestamp -- the ordering key candidates are compared by. Never a
   *  parsed calver: see ci-daemon-autoadopt-plan.md §2.6. */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /** When the probe last ran, or null while {@link #verdict} is still {@link
   *  CiDaemonPinVerdict#UNPROVEN}. */
  @Column(name = "probed_at")
  public Instant probedAt;

  /** The probe's own account of its verdict -- a docker logs tail for a REJECTED candidate, the
   *  reason for an UNKNOWN one. Null while unproven. */
  @Column(columnDefinition = "text")
  public String detail;
}
