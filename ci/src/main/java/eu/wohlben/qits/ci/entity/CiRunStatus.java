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
 * <p>The two <b>active</b> states are {@code QUEUED} and {@code RUNNING}; the three terminal ones
 * are {@code SUCCESS}, {@code FAILED} and {@code CONFIG_ERROR}. That split is what {@code GET
 * /ci/api/runs/active} answers with, and there is deliberately no separate "cancelled" state: a
 * cancelled run is {@code FAILED}, exactly as it was before a queue was visible.
 */
public enum CiRunStatus {
  QUEUED,
  RUNNING,
  SUCCESS,
  FAILED,
  CONFIG_ERROR
}
