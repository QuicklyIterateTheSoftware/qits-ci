package eu.wohlben.qits.ci.entity;

import eu.wohlben.qits.eventstream.CausationStamp;
import eu.wohlben.qits.eventstream.CausedRow;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One CI pipeline execution for one (push, updated branch ref) whose pushed commit carried {@code
 * .config/qits/ci-post-receive.yml}. {@link #repoId} is a plain string — ci lives in its own
 * physical DB with NO FK into qits' tables (a deleted repository leaves runs behind as dangling
 * history, the artifacts stance). Steps are {@link CiStep} rows keyed by {@link CiStep#runId}, not
 * a JPA relation.
 *
 * <p><b>A {@link CausedRow}, beside its own richer record.</b> {@link #causationId} is the
 * platform's generic trace column, and it is filled two ways because the two accept paths stand on
 * different threads. A bus-arrived event crosses the trigger queue before the row is written, and
 * an executor hop is exactly where the ambient {@code CausationScope} dies — so {@code
 * CiRunService.acceptEventRun} sets the cause <em>explicitly</em> from the event id, the same way
 * every other provenance column crosses that hop, and the {@code CausationStamp} listener yields to
 * the set value. A manual trigger evaluates on the request thread, where the REST filter's restored
 * scope still stands — there the stamp itself fills the column, from the {@code
 * X-Qits-Causation-Id} the caller sent. {@link #triggerEventId} stays what it is: domain data with
 * a unique constraint on it. For an event run the two agree; a post-receive run has neither; a
 * manual trigger over REST records a cause where {@code triggerEventId} records none.
 */
@Entity
@Table(name = "ci_run")
@EntityListeners(CausationStamp.class)
public class CiRun extends PanacheEntityBase implements CausedRow {

  @Id public String id;

  /** See the class javadoc; the platform's uniform column, never part of any constraint. */
  @Column(name = "causation_id")
  public UUID causationId;

  @Override
  public UUID causationId() {
    return causationId;
  }

  @Override
  public void causationId(UUID id) {
    this.causationId = id;
  }

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

  /** Why this run was cancelled, or null when it ended for any other reason. */
  @Column(name = "cancellation_reason", length = 255)
  public String cancellationReason;

  /**
   * The newer run that superseded this queued run, or null for every non-deduplication outcome.
   * A plain id rather than a JPA relation: runs are the aggregate and clients use this as a link.
   */
  @Column(name = "superseded_by_run_id", length = 255)
  public String supersededByRunId;

  /**
   * Which {@code qits-ci-daemon} build produced this run's results — resolved once when the run is
   * created and repeated into every one of its step containers, so a deploy landing mid-run cannot
   * make step 3 speak a different protocol than step 1, and the row records forever what ran it.
   *
   * <p>Null on a run that never launched a container — one still {@code QUEUED}, a {@code
   * CONFIG_ERROR}, one cancelled before it started — and on every run recorded before the daemon
   * existed. It is written when the first container is about to launch rather than when the row is
   * inserted, which is what keeps that true now that the row predates the pin.
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

  /** The triggering event's original timestamp, needed to reconstruct its step environment. */
  @Column(name = "trigger_event_occurred_at")
  public Instant triggerEventOccurredAt;

  /** The triggering event's canonical JSON payload, preserved verbatim across a restart. */
  @Column(name = "trigger_event_payload", columnDefinition = "text")
  public String triggerEventPayload;

  /**
   * The exact trigger file that matched this event. Parsing this snapshot, rather than the current
   * branch head, makes a recovered run execute the pipeline it originally accepted.
   */
  @Column(name = "trigger_config", columnDefinition = "text")
  public String triggerConfig;

  /**
   * Which committed file declared this run's pipeline: {@code CiConfigParser.CONFIG_PATH} on a push,
   * the matching {@code .config/qits/ci-event-*.yml} on an event. Never null — it is the third column
   * of the unique constraint, and identity rather than description: two trigger files in one
   * repository matching one event are two runs by design, because they are two declared pipelines.
   */
  @Column(name = "config_path", nullable = false, length = 512)
  public String configPath;
}
