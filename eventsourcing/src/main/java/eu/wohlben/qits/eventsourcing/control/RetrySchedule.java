package eu.wohlben.qits.eventsourcing.control;

import java.time.Duration;

/**
 * How long to wait before the next try — for the outbox, and for the subscriber's redial.
 *
 * <p>A pure function of an attempt count, kept out of both callers so the arithmetic is testable
 * without a clock, a database or a socket.
 */
public final class RetrySchedule {

  /**
   * The outbox schedule: {@code 1s · 4^(attempts-1)}, capped at five minutes.
   *
   * <p>{@code attempts} is the number of delivery attempts <b>already made</b>, the inline one
   * included, so the spacing after each failure runs 1s, 4s, 16s, 64s, 4m16s. With the shipped
   * budget of five attempts only the first four of those are ever used — the fifth failure is
   * terminal — and the cap likewise never binds. Both are here because the formula is the
   * specification, and raising {@code qits.eventsourcing.max-attempts} must extend the schedule
   * rather than discover it has none.
   */
  public static Duration outboxBackoff(int attempts) {
    Duration backoff = Duration.ofSeconds(1);
    for (int i = 1; i < Math.max(1, attempts); i++) {
      backoff = backoff.multipliedBy(4);
      if (backoff.compareTo(OUTBOX_CAP) >= 0) {
        return OUTBOX_CAP;
      }
    }
    return backoff;
  }

  /**
   * The subscriber's redial schedule: the initial delay doubled per consecutive failure, capped.
   *
   * <p>Gentler than the outbox's factor of four because a dropped stream is the ordinary
   * consequence of qits-events restarting, and the cost of reconnecting a little eagerly is one
   * handshake — whereas the events missed while disconnected are not replayed, so a long backoff is
   * a real loss rather than merely a delay.
   */
  public static Duration redialBackoff(int consecutiveFailures, Duration initial, Duration max) {
    Duration backoff = initial;
    for (int i = 0; i < consecutiveFailures; i++) {
      backoff = backoff.multipliedBy(2);
      if (backoff.compareTo(max) >= 0) {
        return max;
      }
    }
    return backoff.compareTo(max) >= 0 ? max : backoff;
  }

  private static final Duration OUTBOX_CAP = Duration.ofMinutes(5);

  private RetrySchedule() {}
}
