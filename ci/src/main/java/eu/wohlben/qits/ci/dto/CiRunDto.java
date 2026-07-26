package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiRunStatus;
import java.time.Instant;
import java.util.List;

/**
 * A CI run as returned to clients — the recorded green/red for one (push, branch). {@code steps} is
 * populated only on the single-run endpoint (with output), null in run listings.
 */
public record CiRunDto(
    String id,
    String repoId,
    String branch,
    String commitSha,
    CiRunStatus status,
    Instant createdAt,
    Instant finishedAt,
    List<CiStepDto> steps) {}
