package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Consumes {@code BuildSuccessful} off the bus and says so. The consuming end of this service's
 * event-bus wiring; {@link BuildSuccessfulAnnouncer} is the other, and the fact that they are the
 * <b>same service</b> is the point rather than an oddity: qits-ci publishes an event, qits-events
 * records and broadcasts it, and qits-ci receives its own announcement back. That round trip is the
 * acceptance test for the whole bus, and this bean is where it becomes visible.
 *
 * <p><b>An INFO log is the whole behaviour, on purpose</b> — and the reason has changed under it,
 * which is worth stating so nobody grows this bean by accident. It used to read "nothing hangs off a
 * green build arriving this way, the deployment path is still the direct POST". The direct POST is
 * gone: qits-platform-deployments consumes this very event durably and deploys from it, so a green
 * build arriving this way is now how the platform deploys at all. That consumer is in <b>that</b>
 * repository, not this one. Here the line stays a line, because a second route to the same outcome
 * on this side would be exactly the duplication the retirement removed.
 *
 * <p><b>Durable, so it is now proof of two things rather than one.</b> It used to be a {@code
 * QitsEventListener<BuildSuccessful>} — the typed, live-only seam — which meant the round trip it
 * demonstrates only held while the socket did. As a {@link QitsDurableEventListener} the same line is
 * logged for an event read back off the log by the catch-up sweep, so a restart or a cutover shows up
 * as a late line rather than as no line at all.
 *
 * <p>Registration is "be a bean": {@code EventDispatcher} injects {@code
 * Instance<QitsDurableEventListener>}, unions {@link #signatures()} into the subscribe frame,
 * dispatches by signature and hands every arrival to the durable funnel. No {@code @Unremovable} is
 * needed — an {@code Instance} injection point is what ArC's unused-bean removal counts as a use.
 *
 * <h2>What this consumer is called, and what it is not</h2>
 *
 * <p>{@link #CONSUMER_ID} is {@code ci-release-train}: the name of the <em>consumption</em>, which is
 * qits-ci watching the platform's green builds go by. It is a storage key rather than a label — it
 * names every {@code consumed_event} row and the watermark — so it survives a rename of this class
 * and must never be handed to a listener that means something else.
 *
 * <p>Worth being exact, because the name invites the wrong reading: the release train's actual
 * membership test is <b>not</b> here. A repository joins a train by committing {@code
 * .config/qits/ci-event-*.yml}, and {@code CiEventTriggerListener} is what evaluates that. This
 * listener has no selection at all, which is why {@code selects} is left at its default: every
 * {@code BuildSuccessful} is one to log.
 *
 * <h2>Late delivery, and the poison case</h2>
 *
 * <p><b>No tip check, because the effect is not last-writer-wins.</b> Catch-up delivers late and out
 * of stream order; a handler that overwrote something would have to collapse against the newest event
 * for its key. This one appends a line to a log. An old event logged after a newer one is a truthful
 * record of a late arrival, and there is nothing for a stale value to roll backwards over.
 *
 * <p><b>An unreadable payload is swallowed with a WARN.</b> The seam retries a throw forever and
 * holds the watermark behind it, so the question to ask of every failure is "could a later attempt
 * ever succeed?". A payload that will not bind never will — it is the same bytes on every offer — so
 * throwing would wedge this consumer's catch-up on one bad event for the sake of one log line. The
 * WARN is the record. A failure that <em>could</em> pass later — the claim's own database being
 * down — never reaches this method; the funnel is already inside that transaction.
 */
@ApplicationScoped
public class BuildSuccessfulListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(BuildSuccessfulListener.class);

  /** The storage key of this consumption — see the class javadoc for what it does and does not mean. */
  static final String CONSUMER_ID = "ci-release-train";

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(BuildSuccessful.class.getSimpleName());
  }

  @Override
  public void onFrame(EventFrame frame) {
    BuildSuccessful event;
    try {
      event = CanonicalJson.payloadTo(frame.payload(), BuildSuccessful.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "BuildSuccessful %s carried a payload this build cannot read, so it is settled unhandled:"
              + " %s",
          frame.id(), unreadable.toString());
      return;
    }
    // The arriving instance is NOT the one that was published: it is rebuilt from the payload, so
    // its eventId is fresh (identity travels in the envelope, and the frame's id below is the real
    // one) while the six fields are the ones that went out.
    LOG.infof(
        "BuildSuccessful received: run %s, commit %s (%s@%s), event %s",
        event.runId(), event.commitSha(), event.repoId(), event.branch(), frame.id());
  }
}
