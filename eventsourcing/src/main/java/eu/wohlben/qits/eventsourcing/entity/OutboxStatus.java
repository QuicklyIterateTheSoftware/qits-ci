package eu.wohlben.qits.eventsourcing.entity;

/**
 * Where an outbox row stands. Two states, because a delivered event is not one: a row that arrives
 * at qits-events is <b>deleted</b>, so the table holds exactly what has not been delivered and its
 * size is a health signal rather than a growing log. The log is qits-events.
 */
public enum OutboxStatus {

  /** Not delivered yet, and {@code nextAttemptAt} says when to try again. */
  PENDING,

  /**
   * Given up on. Either the retry budget ran out, or qits-events answered 400 — which means the
   * UUID was reused with different content and no number of retries will change the answer. Rows
   * stay so the failure is visible; nothing sweeps them.
   */
  FAILED
}
