package eu.wohlben.qits.ci.entity;

/**
 * Lifecycle of a CI run. {@link #CONFIG_ERROR} is the "broken gate is visible rather than silently
 * green" state: the pushed commit carried a config file that could not be parsed (or a step missed
 * {@code script}/{@code image}), so no steps ran.
 *
 * <p>{@link #QUEUED} is the accepted-but-not-started state, and it is written at <b>accept</b> time
 * — by the intake and by the trigger engine's enqueue — rather than when the worker gets to the run.
 * Before it existed, a queued run was a closure on a single-threaded executor and nothing else: it
 * was invisible to every read surface and it died with the process, which is the "lossy intake"
 * operators used to replay a post-receive around. A {@code QUEUED} row is durable, so a restart
 * re-enqueues it instead of losing it.
 *
 * <p>The two <b>active</b> states are {@code QUEUED} and {@code RUNNING}; the terminal ones are
 * {@code SUCCESS}, {@code FAILED}, {@code CANCELLED} and {@code CONFIG_ERROR}. A cancellation is a
 * user decision rather than a failed pipeline verdict, so it has its own terminal state.
 */
public enum CiRunStatus {
  QUEUED,
  RUNNING,
  SUCCESS,
  FAILED,
  CANCELLED,
  CONFIG_ERROR
}
