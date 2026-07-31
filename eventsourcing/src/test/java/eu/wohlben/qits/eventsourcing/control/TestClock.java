package eu.wohlben.qits.eventsourcing.control;

import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the suite moves by hand. Being a bean of type {@link Clock} is the whole mechanism — it
 * outranks {@code EventsourcingClock}'s {@code @DefaultBean} producer with no alternative and no
 * priority — so the sweeper and the outbox read this everywhere in the test application.
 *
 * <p>It exists so the retry schedule can be walked in milliseconds instead of the eighty-odd
 * seconds it really spans. A test that slept through the real backoff would be a test nobody runs.
 */
@Singleton
public class TestClock extends Clock {

  private volatile Instant now = Instant.parse("2026-07-31T12:00:00Z");

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  /** Put the clock at a known instant — every test starts from the same one. */
  public void set(Instant instant) {
    now = instant;
  }

  /** Move forward. */
  public void advance(Duration by) {
    now = now.plus(by);
  }

  /** Move to exactly when something became due, which is how a backoff is asserted rather than waited out. */
  public void jumpTo(Instant instant) {
    now = instant;
  }
}
