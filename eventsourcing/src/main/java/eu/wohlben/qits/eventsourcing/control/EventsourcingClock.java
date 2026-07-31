package eu.wohlben.qits.eventsourcing.control;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * The module's clock, as a bean.
 *
 * <p>Everything time-dependent here — when a row may be retried next, whether it is due — is
 * arithmetic on instants, and the only way to test arithmetic on instants without sleeping through
 * it is to be able to move the clock. So the retry schedule is exercised by advancing a test clock
 * across five attempts in milliseconds rather than by waiting the eighty-odd seconds the real
 * schedule spans.
 *
 * <p>{@code @DefaultBean} is what makes that free: a test that declares its own {@code Clock} bean
 * simply outranks this one, with no alternative, no priority and no profile.
 */
@ApplicationScoped
public class EventsourcingClock {

  @Produces
  @Singleton
  @DefaultBean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
