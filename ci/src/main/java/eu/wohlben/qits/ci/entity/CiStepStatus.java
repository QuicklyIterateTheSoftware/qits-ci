package eu.wohlben.qits.ci.entity;

/**
 * Lifecycle of a single step. Steps run sequentially; the first {@link #FAILED} step fails the run
 * and leaves the remaining steps {@link #SKIPPED}.
 */
public enum CiStepStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILED,
  SKIPPED
}
