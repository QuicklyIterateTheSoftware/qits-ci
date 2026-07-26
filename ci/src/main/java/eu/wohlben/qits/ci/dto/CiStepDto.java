package eu.wohlben.qits.ci.dto;

import eu.wohlben.qits.ci.entity.CiStepStatus;

/**
 * One step of a run as returned to clients. {@code output} is the bounded, tail-truncated combined
 * stdout+stderr — populated only on the single-run endpoint, null in run listings.
 */
public record CiStepDto(
    int stepIndex, String image, CiStepStatus status, Integer exitCode, String output) {}
