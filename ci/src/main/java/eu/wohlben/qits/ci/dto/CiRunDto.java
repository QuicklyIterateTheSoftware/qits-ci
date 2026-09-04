package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import java.time.Instant;
import java.util.List;

/**
 * A CI run as returned to clients — the recorded green/red for one (push, branch). {@code steps} is
 * populated only on the single-run endpoint (with output), null in run listings.
 *
 * <p>{@code live} is the step currently executing and what it has printed so far, read from the
 * in-memory relay rather than from the database: non-null only on the single-run endpoint, and only
 * while {@code status} is {@code RUNNING}. Steps are persisted at their end, so mid-run {@code
 * steps} legitimately holds fewer entries than the pipeline declared, and {@code live} is what says
 * which step the gap belongs to instead of leaving it looking like a run with missing steps.
 *
 * <p><b>{@code QUEUED} is a run that has been accepted and not started</b>, and a client must treat
 * it as non-terminal: it has no {@code finishedAt}, no {@code daemonVersion}, no steps and no {@code
 * live} — there is nothing running to be live about — and it will move on its own, so keep polling.
 * The two active statuses are {@code QUEUED} and {@code RUNNING}; terminal statuses include the
 * distinct {@code CANCELLED} outcome.
 *
 * <p>{@code daemonVersion} is the {@code qits-ci-daemon} build every one of this run's containers
 * ran, pinned once at run creation — so the row records forever what produced its results.
 *
 * <p>{@code projectId} and {@code repoName} are the repository's <b>public</b> coordinate — the one
 * address the platform speaks, {@code /git/<projectId>/<repoName>} — and they are additive rather
 * than a replacement: {@code repoId} stays the storage key every existing client already binds and
 * every run row is found by. Both are <b>null</b> when the announcing push was id-addressed and on
 * every run recorded before the identity campaign, so a client labels by {@code repoName} when it is
 * there and falls back to {@code repoId} when it is not.
 *
 * <p>The four <b>provenance</b> fields say what caused the run. {@code triggerType} is {@code
 * POST_RECEIVE} or {@code EVENT}; {@code configPath} is the committed file that declared the
 * pipeline, which on an event-triggered run identifies <em>which</em> {@code
 * .config/qits/ci-event-*.yml} matched; {@code triggerEventId} and {@code triggerEventName} are the
 * event that caused it, null on every push. They are exposed here because the run API is where an
 * operator reads a run's provenance from outside — no client renders them yet, and that is a later,
 * small follow-up rather than a gap.
 *
 * <p>{@code releaseRequestId} is the fifth of them and the one that is not about a commit: the
 * release request whose backing branch this run built, null for every run that serves none. The
 * {@code commitSha} beside it is a fold nobody pushed and is replaced by the next re-fold, so this
 * is the handle that says which piece of work the run belongs to.
 *
 * <p>{@code retryOfRunId} is the sixth, and it is the only one that names another run: the run this
 * one was fired to re-do, null on everything a trigger produced. A client renders it as a link back
 * and reads it as "this row is a re-fire" — the {@code triggerEventId} beside it is then a synthetic
 * token rather than a foreign event id, so nothing should be matched against the event log by it.
 *
 * <p><b>{@code gating} on a FINISHED run is what the verdict was worth</b>, not only what the
 * pipeline declared: a gating pipeline whose failure happened in a step declaring {@code gating:
 * false} reads {@code false} here, which is the same value its build event carried.
 */
public record CiRunDto(
    String id,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    boolean gating,
    CiRunStatus status,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt,
    String cancellationReason,
    String supersededByRunId,
    String daemonVersion,
    CiTriggerType triggerType,
    String triggerEventId,
    String triggerEventName,
    String releaseRequestId,
    String retryOfRunId,
    String configPath,
    List<CiStepDto> steps,
    CiLiveStepDto live) {}
