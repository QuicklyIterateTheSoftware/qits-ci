package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiStepStatus;
import java.time.Instant;

/**
 * One step of a run as returned to clients. {@code output} is the bounded, tail-truncated combined
 * stdout+stderr — populated only on the single-run endpoint, null in run listings.
 *
 * <p>A step only ever appears here already finished: while it runs it has no row, and the run's
 * {@code live} object carries its output instead. The two timestamps are host-stamped, and null on
 * a skipped step.
 */
public record CiStepDto(
    int stepIndex,
    String image,
    CiStepStatus status,
    Integer exitCode,
    Instant startedAt,
    Instant finishedAt,
    String output) {}
