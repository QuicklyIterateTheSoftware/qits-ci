package eu.wohlben.qits.ci.entity;

/**
 * Lifecycle of a CI run. {@link #CONFIG_ERROR} was the "broken gate is visible rather than silently
 * green" state: a pushed commit carried a config file that could not be parsed (or a step missed
 * {@code script}/{@code image}), so no steps ran. <b>Nothing writes it any more</b> — the push
 * intake that read config on the run worker retired on 2026-09-05, and a trigger file is parsed
 * before a row exists, so a broken one is a WARN and no run. The constant stays because rows carry
 * it and because the column is {@code @Enumerated(EnumType.STRING)}.
 *
 * <p>{@link #QUEUED} is the accepted-but-not-started state, and it is written at <b>accept</b> time
 * — by the trigger engine's enqueue — rather than when the worker gets to the run. Before it
 * existed, a queued run was a closure on a single-threaded executor and nothing else: it was
 * invisible to every read surface and it died with the process. A {@code QUEUED} row is durable, so
 * a restart re-enqueues it instead of losing it.
 *
 * <p>The two <b>active</b> states are {@code QUEUED} and {@code RUNNING}; the terminal ones are
 * {@code SUCCESS}, {@code FAILED}, {@code CANCELLED}, {@code CONFIG_ERROR} and {@code TIMED_OUT}. A
 * cancellation is not a failed pipeline verdict, so it has its own terminal state, and a deadline is
 * not a verdict either — {@link #TIMED_OUT} says the run ran out of time.
 *
 * <p><b>{@code FAILED} is a statement about the commit; every other terminal state is a statement
 * about the run.</b> That is the line the vocabulary draws, and it is why a run superseded by a
 * newer one settles {@code CANCELLED} rather than {@code FAILED}: nobody is waiting for its answer
 * any more, and it never produced one. Which cancellation it was is {@code ci_run.cancellation_reason}
 * — {@code USER_CANCELLED}, {@code RELEASE_REQUEST_CANCELLED} or {@code DEDUPED} — so the status
 * says "no verdict" and the reason says why, instead of a red row saying something untrue about a
 * commit that was never built.
 */
public enum CiRunStatus {
  QUEUED,
  RUNNING,
  SUCCESS,
  FAILED,
  CANCELLED,
  CONFIG_ERROR,
  /** A step hit its deadline and was aborted, so the run ended on the clock, not on a verdict. */
  TIMED_OUT
}
