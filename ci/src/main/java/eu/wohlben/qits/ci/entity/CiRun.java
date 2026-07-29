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
 * One CI pipeline execution for one (push, updated branch ref) whose pushed commit carried {@code
 * .config/qits/ci-post-receive.yml}. {@link #repoId} is a plain string — ci lives in its own
 * physical DB with NO FK into qits' tables (a deleted repository leaves runs behind as dangling
 * history, the artifacts stance). Steps are {@link CiStep} rows keyed by {@link CiStep#runId}, not
 * a JPA relation.
 */
@Entity
@Table(name = "ci_run")
public class CiRun extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "repo_id", nullable = false)
  public String repoId;

  @Column(nullable = false)
  public String branch;

  @Column(name = "commit_sha", nullable = false, length = 64)
  public String commitSha;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiRunStatus status;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  /**
   * Which {@code qits-ci-daemon} build produced this run's results — resolved once when the run is
   * created and repeated into every one of its step containers, so a deploy landing mid-run cannot
   * make step 3 speak a different protocol than step 1, and the row records forever what ran it.
   *
   * <p>Null on a run that never launched a container (a {@code CONFIG_ERROR}), and on every run
   * recorded before the daemon existed.
   */
  @Column(name = "daemon_version", length = 64)
  public String daemonVersion;
}
