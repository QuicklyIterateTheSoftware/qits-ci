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
 * One step of a {@link CiRun}, in declaration order ({@link #stepIndex}). {@link #output} is the
 * combined stdout+stderr of the step's container, bounded and tail-truncated while it arrives.
 *
 * <p><b>A row is written once, already terminal.</b> There is no insert-then-update: while a step
 * runs it has no row at all and the live output is the in-memory relay, and the row appears at the
 * step's end carrying its final status, exit code, timestamps and tail. So the database never holds
 * a half-written step, and a crash mid-run cannot leave one claiming to still be executing.
 * {@link CiStepStatus#PENDING} and {@link CiStepStatus#RUNNING} survive in the enum for rows written
 * before that was true, and are never written again.
 */
@Entity
@Table(name = "ci_step")
public class CiStep extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "run_id", nullable = false)
  public String runId;

  @Column(name = "step_index", nullable = false)
  public int stepIndex;

  @Column(nullable = false, length = 512)
  public String image;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiStepStatus status;

  @Column(name = "exit_code")
  public Integer exitCode;

  /**
   * When the host handed this step's script to its container's daemon.
   *
   * <p><b>Host-stamped, never daemon-reported.</b> A container turns hostile the moment step code
   * runs in it, and a clock claim is the cheapest thing to forge — so both timestamps are taken on
   * this side, at the two moments the host knows about first-hand: the {@code RunStep} it sent, and
   * the terminal frame it received (or the deadline that fired instead). A step that never got that
   * far falls back to the moment the host began working on it, and a {@code SKIPPED} step carries
   * neither.
   */
  @Column(name = "started_at")
  public Instant startedAt;

  /** When the step's terminal frame arrived, or its deadline fired. Host-stamped; see above. */
  @Column(name = "finished_at")
  public Instant finishedAt;

  /**
   * The step's combined output, bounded and tail-truncated while it arrives.
   *
   * <p>{@code columnDefinition = "text"} rather than {@code @Lob}, and that is the one mapping the
   * move to postgres had to change. On H2 a {@code @Lob String} was a clob and the two agreed; on
   * postgres {@code @Lob} means a LARGE OBJECT — Hibernate binds an oid and the insert fails against
   * the {@code text} column the migration creates. Unbounded either way.
   */
  @Column(columnDefinition = "text")
  public String output;
}
