package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiRunStatus;
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
 * <p>{@code daemonVersion} is the {@code qits-ci-daemon} build every one of this run's containers
 * ran, pinned once at run creation — so the row records forever what produced its results.
 */
public record CiRunDto(
    String id,
    String repoId,
    String branch,
    String commitSha,
    CiRunStatus status,
    Instant createdAt,
    Instant finishedAt,
    String daemonVersion,
    List<CiStepDto> steps,
    CiLiveStepDto live) {}
