package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One {@code SCMRelease} this instance has seen: the durable half of the release join that says a
 * {@code (repository, version)} really was released. Only qits-workspaces publishes that event, so a
 * row here is the novelty a bootstrap replay does not have — see {@code ReleaseJoin}.
 *
 * <p>Kept forever rather than pruned. A green release run may arrive arbitrarily later than the
 * release it belongs to, and a fact that expired would turn a slow build into a silent replay.
 *
 * <p>{@link #repoName} is the same repository under its registered name, which {@code SCMRelease}
 * carries beside the id and may leave null. The lookup matches either spelling: a run's {@code
 * repoId} is the git host's id, and on this platform the two agree — but the event's own javadoc does
 * not promise it, and a join that silently missed would be a release nobody announces.
 *
 * <p>{@code @Uncaused} by decision, the {@link CiDaemonPin} argument verbatim: {@link #eventId} IS
 * the causing event, already on the row, so a generic causation column would be that column under a
 * second name.
 */
@Entity
@Table(name = "ci_scm_release")
@Uncaused
public class CiScmRelease extends PanacheEntityBase {

  @Id public String id;

  /** The repository that released, by the id the event carries. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** The same repository by its registered name, or null when the event carried none. */
  @Column(name = "repo_name")
  public String repoName;

  @Column(nullable = false)
  public String version;

  /** The announcing {@code SCMRelease}'s own id. */
  @Column(name = "event_id", nullable = false)
  public String eventId;

  /** When the release happened, as the event reports it. */
  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;

  /** When this instance recorded it. */
  @Column(name = "seen_at", nullable = false)
  public Instant seenAt;
}
