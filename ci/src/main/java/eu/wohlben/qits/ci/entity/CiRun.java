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
 * One CI pipeline execution: one domain event matched one repository's {@code
 * .config/qits/ci-event-*.yml}, and this is the row that pipeline ran under. (Rows recorded before
 * 2026-09-05 may instead be one (push, updated branch ref) whose commit carried {@code
 * .config/qits/ci-post-receive.yml} — that intake retired, its rows did not.) {@link #repoId} is a
 * plain string — ci lives in its own
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
 * a unique constraint on it. For an event run the two agree; a historical post-receive run has
 * neither; a manual trigger over REST records a cause where {@code triggerEventId} records none.
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

  /**
   * The public coordinate of the same repository — the project it belongs to and its name within
   * that project, which together are the one address anything above the projects↔githost seam
   * speaks: {@code /git/<projectId>/<repoName>}.
   *
   * <p><b>Both are nullable, and that is the compatibility arm rather than an oversight.</b> The git
   * host fills them on an {@code SCM*} event from the address the push arrived on, so a push on the
   * internal id-addressed route announces without them and every row recorded before this campaign
   * has neither. A run with no pair builds id-addressed URLs and displays its id, which is exactly
   * what this service did before names existed. {@link #repoId} stays the storage-adjacent key: it
   * is what the dedupe constraint is built on and what every historical row is found by, and it is
   * never displayed once a name is there.
   */
  @Column(name = "project_id", length = 255)
  public String projectId;

  @Column(name = "repo_name", length = 255)
  public String repoName;

  @Column(nullable = false)
  public String branch;

  @Column(name = "commit_sha", nullable = false, length = 64)
  public String commitSha;

  /**
   * Whether a red outcome of this run should stand in the way of releasing its commit. True for
   * every event run whose trigger file does not say {@code gating: false} — the userflow pipelines
   * are the ones that do — and on every historical push row, which had no file-level flag to say
   * otherwise. Initialized true so no writer can forget it into
   * the primitive default, which points the wrong way.
   *
   * <p><b>It is written twice on a run whose failure was non-gating.</b> Accept time records what
   * the trigger file declared; the terminal transition records what the <em>verdict</em> is worth,
   * which is the file's flag ANDed with the failing step's own {@code gating:} — see {@code
   * CiPipeline.CiStepDecl}. So the row and the build event it publishes never disagree, and reading
   * this column off a finished run answers the question a release gate asks.
   */
  @Column(nullable = false)
  public boolean gating = true;

  /**
   * The release request this run serves, or null for every run that serves none — which is every
   * event run not triggered by a {@code ReleaseRequestChanged}, and every historical push row.
   *
   * <p>A release request is qits-projects' aggregate and this is its id as a plain string, the way
   * this module names every foreign thing. It is recorded because the run is <b>about</b> that
   * request rather than merely about a commit: the run's commit sha is a fold nobody pushed (the tip
   * of {@code release/<id>}, rewritten on every re-fold), so the request id is the only stable
   * handle a cancellation or a retry can address the work by. {@link #commitSha} is the merged sha
   * the event named, so the two together say exactly which fold this verdict is about.
   *
   * <p>Nullable and part of no constraint: the dedupe stays {@code (trigger_event_id, repo_id,
   * config_path)}, and the per-branch collapse a re-fold needs is already
   * {@code CiRunService.supersedeByCheckoutBranch}'s — the backing branch is stable per request, so
   * a burst of re-folds collapses to the newest tip with nothing added here.
   */
  @Column(name = "release_request_id", length = 255)
  public String releaseRequestId;

  /**
   * The run this one re-fires, or null for every run that is not a manual retry — which is every run
   * a trigger produced.
   *
   * <p>A retry asks for the <b>same work</b> again: same repository, same trigger file, same
   * checkout, same {@link #releaseRequestId}, same {@link #commitSha}, so its verdict correlates
   * exactly as the original's would have. What it may not carry is the same {@link #triggerEventId},
   * because that column is one third of the dedupe constraint and a second row under the original
   * event id is the replay the constraint exists to refuse. A retry therefore mints its own
   * <b>synthetic</b> trigger identity, {@code CiRunService.RETRY_TRIGGER_PREFIX + id} — unique by
   * construction, and naming no event qits-events ever minted.
   *
   * <p>This column is the provenance that synthetic id cannot carry, and it is also what tells the
   * two apart at read time: a row with a value here is a re-fire, and its {@code triggerEventId} is
   * a local token rather than a foreign id. {@link #causationId} is copied from the run being
   * retried, so the events the retry publishes still name the domain event that started all of it.
   */
  @Column(name = "retry_of_run_id", length = 255)
  public String retryOfRunId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CiRunStatus status;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /** When the worker claimed this queued run and pipeline execution actually began. */
  @Column(name = "started_at")
  public Instant startedAt;

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
   * The id of the domain event that caused this run. Null only on a historical {@code POST_RECEIVE}
   * row: a push was not caused by an event, and the intake that recorded one retired on 2026-09-05.
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
   * already-triggered rather than re-run. Rows with a null here are all distinct to that constraint —
   * plain SQL {@code unique}, where rows collide only when every column is non-null and equal —
   * which is what kept every post-receive run out of its way and now applies to nothing a live
   * deployment writes.
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
   * Which committed file declared this run's pipeline: the matching {@code
   * .config/qits/ci-event-*.yml}, or {@code .config/qits/ci-post-receive.yml} on a historical push
   * row. Never null — it is the third column
   * of the unique constraint, and identity rather than description: two trigger files in one
   * repository matching one event are two runs by design, because they are two declared pipelines.
   */
  @Column(name = "config_path", nullable = false, length = 512)
  public String configPath;
}
