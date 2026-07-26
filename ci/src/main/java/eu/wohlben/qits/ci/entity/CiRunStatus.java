package eu.wohlben.qits.ci.entity;

/**
 * Lifecycle of a CI run. {@link #CONFIG_ERROR} is the "broken gate is visible rather than silently
 * green" state: the pushed commit carried a config file that could not be parsed (or a step missed
 * {@code script}/{@code image}), so no steps ran.
 */
public enum CiRunStatus {
  RUNNING,
  SUCCESS,
  FAILED,
  CONFIG_ERROR
}
