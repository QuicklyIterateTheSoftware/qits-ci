package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.eventstream.QitsEventListener;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Consumes {@code BuildSuccessful} off the bus and says so. The consuming end of this service's
 * event-bus wiring; {@link BuildSuccessfulAnnouncer} is the other, and the fact that they are the
 * <b>same service</b> is the point rather than an oddity: qits-ci publishes an event, qits-events
 * records and broadcasts it, and qits-ci receives its own announcement back over the stream. That
 * round trip is the acceptance test for the whole bus, and this bean is where it becomes visible.
 *
 * <p><b>An INFO log is the whole behaviour, on purpose.</b> Nothing yet hangs off a green build
 * arriving this way — the deployment path is still {@code CdBuildNotifier}'s direct POST — so
 * anything more here would be a second, competing route to the same outcome. When a real consumer
 * appears, it is another bean beside this one and this one stays as the proof the stream is live.
 *
 * <p>Registration is "be a bean": {@code EventDispatcher} injects {@code
 * Instance<QitsEventListener<?>>}, derives the subscription set from every listener's {@link
 * #eventType()} and dispatches by signature, and {@code EventStreamSubscriber} dials on startup
 * because this bean exists (with none, it does not dial at all). No {@code @Unremovable} is needed —
 * an {@code Instance} injection point is what ArC's unused-bean removal counts as a use.
 *
 * <p>The arriving instance is <b>not</b> the one that was published: it is rebuilt from the frame's
 * payload, so its {@code eventId} is fresh (identity travels in the envelope) while the six fields
 * below are the ones that went out.
 */
@ApplicationScoped
public class BuildSuccessfulListener implements QitsEventListener<BuildSuccessful> {

  private static final Logger LOG = Logger.getLogger(BuildSuccessfulListener.class);

  @Override
  public Class<BuildSuccessful> eventType() {
    return BuildSuccessful.class;
  }

  @Override
  public void onEvent(BuildSuccessful event) {
    LOG.infof(
        "BuildSuccessful received: run %s, commit %s (%s@%s)",
        event.runId(), event.commitSha(), event.repoId(), event.branch());
  }
}
