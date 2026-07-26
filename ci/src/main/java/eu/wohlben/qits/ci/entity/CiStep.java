package eu.wohlben.qits.ci.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * One step of a {@link CiRun}, in declaration order ({@link #stepIndex}). {@link #output} is the
 * combined stdout+stderr of the step's container, bounded and tail-truncated by the run service
 * before persisting.
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

  @Lob public String output;
}
