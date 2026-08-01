package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.RunAnnouncer;
import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a green run into the platform's {@code BuildSuccessful} event and hands it to the bus. The
 * producing end of this service's event-bus wiring; {@link BuildSuccessfulListener} is the other.
 *
 * <p>It lives in {@code service/} because the {@code ci} module knows nothing of the bus; the seam
 * it implements is {@link RunAnnouncer} in {@code ci/control}, and zero implementations is a
 * supported configuration.
 *
 * <p><b>{@code imageDigest} is null, and that is a fact about qits-ci rather than a gap here.</b>
 * A pipeline that publishes an image does it inside its own step container, with its own CLI,
 * against a registry this process never talks to — all that comes back over the daemon socket is an
 * exit code and output. There is no digest at the SUCCESS transition to pass on, so the field is
 * omitted from the canonical payload entirely (an absent field is not written as an explicit null).
 * The event class carries it because the shape of the announcement is the platform's rather than
 * this service's, and the day a step reports what it pushed, this is the single line that changes.
 *
 * <p><b>It blocks, briefly, and that was the trade.</b> {@link QitsEventBus#publish} attempts the
 * PUT inline, never throws, and gives up after the publish timeout — after which the outbox owns
 * delivery. The caller is the single-threaded run worker, so a qits-events that is down costs every
 * green build those few seconds once and nothing after; see {@link RunAnnouncer}.
 *
 * <p><b>This is where the platform's first automatic causation edge is drawn</b>, and {@link
 * CausingEvent} is the whole of how: the {@code triggerEventId} the port carries is the event that
 * caused the run, read off the run's own row, and it is handed to {@code publish(event, parent)} as
 * an explicit argument. So an event-triggered run's {@code BuildSuccessful} names the event that
 * triggered it, and a release train is a chain in the log rather than a set of rows distinguishable
 * from coincidence only by their timestamps.
 *
 * <p><b>It announces every green run and only that.</b> {@link SoftwareReleaseAnnouncer} is the
 * other producer on this bus and it is additional, never a replacement — a release pipeline's run
 * publishes this event and then one {@code SoftwareRelease} per artifact it declared.
 */
@ApplicationScoped
public class BuildSuccessfulAnnouncer implements RunAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onRunSucceeded(
      String runId,
      String repoId,
      String branch,
      String commitSha,
      Instant finishedAt,
      String triggerEventId) {
    bus.publish(
        new BuildSuccessful(runId, repoId, branch, commitSha, null, finishedAt),
        CausingEvent.parentOf(triggerEventId, runId));
  }
}
