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

  /**
   * Which trigger produced this run. Never null — rows recorded before event triggers existed were
   * backfilled {@link CiTriggerType#POST_RECEIVE}, which is what they were.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 32)
  public CiTriggerType triggerType;

  /**
   * The id of the domain event that caused this run, or null on a {@code POST_RECEIVE} run — a push
   * is not caused by an event.
   *
   * <p><b>It is the carrier across a thread hop.</b> The engine consumes a frame on the bus's
   * dispatch thread and <em>enqueues</em> the run, which executes later on {@code ci-run-worker}; a
   * {@code CausationScope} thread-local is long gone by then, deliberately (it does not follow work).
   * This column is what survives that hop <em>and</em> a restart, and it is what {@code
   * RunAnnouncer.onRunSucceeded} passes to {@code publish(event, parent)} — so the events a triggered
   * run publishes name what caused them, and a release train is a chain in the log rather than a set
   * of rows distinguishable from coincidence only by their timestamps.
   *
   * <p>With {@link #repoId} and {@link #configPath} it carries a <b>unique constraint</b>, which is
   * the durable at-most-one-run-per-(event, trigger file) guarantee. A redelivery of the same event —
   * legal, and something a future catch-up feature will do on purpose — hits it and is dropped as
   * already-triggered rather than re-run. Rows with a null here are all distinct to that constraint,
   * which is what keeps every post-receive run out of its way.
   */
  @Column(name = "trigger_event_id", length = 255)
  public String triggerEventId;

  /** The name of the event that caused this run, recorded beside its id so the row reads. */
  @Column(name = "trigger_event_name", length = 255)
  public String triggerEventName;

  /**
   * Which committed file declared this run's pipeline: {@code CiConfigParser.CONFIG_PATH} on a push,
   * the matching {@code .config/qits/ci-event-*.yml} on an event. Never null — it is the third column
   * of the unique constraint, and identity rather than description: two trigger files in one
   * repository matching one event are two runs by design, because they are two declared pipelines.
   */
  @Column(name = "config_path", nullable = false, length = 512)
  public String configPath;
}
