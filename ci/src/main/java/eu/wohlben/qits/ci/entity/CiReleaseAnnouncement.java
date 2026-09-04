package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One {@code SoftwareRelease} a green release pipeline <b>owes</b>: the artifact it declared, the
 * version it published under, and — once the release fact is in — when the announcement was made.
 *
 * <p>{@link #announcedAt} null is the whole of "still owed". The row stays after the announcement as
 * the record that it happened, so this table reads as the account of every artifact this instance
 * published and of every one that published without a release behind it.
 *
 * <p>{@link #runId} is a plain string and not a relation: the obligation must outlive whatever
 * happens to the run row, which is the same reason {@code superseded_by_run_id} is not a foreign key
 * either.
 *
 * <p>{@code @Uncaused} by decision: {@link #triggerEventId} IS the cause — the event that triggered
 * the run, already on the row because it is what the published event is stamped with as its parent —
 * so a generic causation column would be that column again under a second name. The {@link
 * CiDaemonPin} argument, applied to a row that names its cause for a different reason.
 */
@Entity
@Table(name = "ci_release_announcement")
@Uncaused
public class CiReleaseAnnouncement extends PanacheEntityBase {

  @Id public String id;

  /** The run that published the artifact. */
  @Column(name = "run_id", nullable = false)
  public String runId;

  /** The repository whose pipeline published it — this repo, not the upstream that triggered it. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /**
   * The project that repository belongs to, copied off the run so the announcement can name it — the
   * announcement may be made minutes after the run finished, or by a boot sweep in another process,
   * and neither can go back and ask the run row.
   *
   * <p>Nullable, and not a relation: it is another context's id, exactly as {@code ci_run.project_id}
   * is. A run whose candidate was id-addressed has none, which is why the published event's key is
   * simply absent rather than null.
   */
  @Column(name = "project_id")
  public String projectId;

  /**
   * The repository's public name, copied off the run beside {@link #projectId} and carried for the
   * same reason: {@code (project_id, repo_name)} is the address a consumer reads the released
   * repository's files at, and neither a later {@code SCMRelease} nor a boot sweep can ask the run
   * row for it.
   *
   * <p>Nullable on exactly the runs {@link #projectId} is null on — an id-addressed candidate has no
   * public name — and it is <b>not</b> part of the join key, which stays {@code (repo_id, version)}.
   */
  @Column(name = "repo_name")
  public String repoName;

  /** Half of the join key, read out of the triggering event — see {@code ReleaseJoin}. */
  @Column(nullable = false)
  public String version;

  /** {@code npm}, {@code maven}, {@code docker} or {@code daemon} — the keyword the trigger file
   *  used, which is also the wire value. */
  @Column(name = "package_type", nullable = false, length = 32)
  public String packageType;

  @Column(name = "package_name", nullable = false, length = 512)
  public String packageName;

  /**
   * Where this artifact sat in the trigger file's {@code artifacts:} list — what the announcements
   * are ordered by. The rows of one run are written in a single instant, so {@link #createdAt} cannot
   * order them and the row id is a random UUID.
   */
  @Column(name = "artifact_index", nullable = false)
  public int artifactIndex;

  /**
   * The run's terminal timestamp, which is when the artifact became available. Carried rather than
   * re-derived, because the announcement may be made minutes later and the event wants the moment
   * the package appeared.
   */
  @Column(name = "finished_at", nullable = false)
  public Instant finishedAt;

  /** The event that caused the run — the published event's parent. Null on a run nothing announced. */
  @Column(name = "trigger_event_id")
  public String triggerEventId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** When the announcement was made, or null while it is still owed. */
  @Column(name = "announced_at")
  public Instant announcedAt;
}
