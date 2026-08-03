package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * DOWN exactly when the daemon pin ladder has fallen all the way through: every adopted candidate
 * {@code REJECTED} and no configured pin either -- {@code CiDaemonPins.Pin.source() ==
 * CiDaemonPins.SOURCE_NONE} (ci-daemon-autoadopt-plan.md §2.5, ⚖6(a)). This is the first health
 * check this service declares of its own; {@code quarkus-smallrye-health} has been on the classpath
 * since before this feature (BV, {@code service/pom.xml:37}).
 *
 * <p><b>What this buys, and what it deliberately does not.</b> {@code daemonVersion()} keeps
 * answering blank and runs keep executing and failing with today's distinguishable states -- nothing
 * here refuses a run (⚖6(b), parked). What DOWN actually reaches is qits-cd's own health gate: a
 * deployment of this service landing with a broken ladder fails {@code awaitHealthy} and cd restores
 * the previous container, which is the single most valuable effect and costs this class nothing
 * beyond existing. It is not self-healing for a container already running -- {@code --restart
 * unless-stopped} does not act on unhealthy -- and the gateway does not remove a DOWN qits-ci from
 * routing, which is correct: {@code GET /ci/api/daemon} must stay answerable to say why.
 *
 * <p>Naming the rejected versions as health data is what makes DOWN actionable rather than merely
 * alarming -- {@code docker inspect} or {@code /q/health/ready} both surface {@link
 * CiDaemonPins#rejectedVersions()} without a second lookup anywhere.
 *
 * <p><b>Reads {@link CiDaemonPins#currentAnswer()}, never {@link CiDaemonPins#answer()}.</b> This
 * check runs on the container healthcheck's own cadence (every few seconds), so calling the probing
 * method here would launch a probe container on that cadence too -- the amplifier that turned one
 * docker container-naming race into a near-certain collision. A DOWN ladder is still DOWN either way
 * -- an unprobed candidate is not {@code PROVEN} regardless of which method walked past it.
 */
@Readiness
@ApplicationScoped
public class CiDaemonReadinessCheck implements HealthCheck {

  private static final String NAME = "ci-daemon-pin";

  @Inject CiDaemonPins pins;

  @Override
  public HealthCheckResponse call() {
    CiDaemonPins.Pin pin = pins.currentAnswer();
    if (!CiDaemonPins.SOURCE_NONE.equals(pin.source())) {
      return HealthCheckResponse.up(NAME);
    }
    List<String> rejected = pins.rejectedVersions();
    return HealthCheckResponse.named(NAME)
        .down()
        .withData(
            "rejectedVersions", rejected.isEmpty() ? "" : String.join(",", rejected))
        .withData("message", "no daemon pin available -- every candidate was rejected and no "
            + "qits.ci.daemon-version is configured")
        .build();
  }
}
