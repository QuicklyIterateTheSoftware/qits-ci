package eu.wohlben.qits.ci.entity;

/**
 * Lifecycle of a single step. Steps run sequentially; the first {@link #FAILED} or {@link
 * #TIMED_OUT} step ends the run and leaves the remaining steps {@link #SKIPPED}.
 *
 * <p><b>{@link #PENDING} and {@link #RUNNING} are legacy values.</b> Step rows are written once and
 * already terminal (see {@link CiStep}), so nothing writes either of them any more — they stay in
 * the enum because rows carrying them exist in databases that predate that change, and {@code
 * CiRunService}'s startup sweep still moves such a row to a terminal state.
 */
public enum CiStepStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILED,
  SKIPPED,
  /**
   * The step hit its deadline and was aborted. A deadline is not a script's verdict, so it does not
   * share {@code FAILED}.
   */
  TIMED_OUT
}
