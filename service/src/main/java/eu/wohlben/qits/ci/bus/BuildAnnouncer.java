package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.RunAnnouncer;
import eu.wohlben.qits.ci.events.BuildFailed;
import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns a finished run into the platform's build event — {@code BuildSuccessful} for a green one,
 * {@code BuildFailed} for a red one — and hands it to the bus. The producing end of this service's
 * event-bus wiring; {@link BuildSuccessfulListener} is the other. It was {@code
 * BuildSuccessfulAnnouncer} while a green run was the only thing announced; the name widened when
 * the failure event joined, the seam did not.
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
 * {@code BuildFailed} carries no digest at all: a failed run published nothing worth naming.
 *
 * <p><b>It blocks, briefly, and that was the trade.</b> {@link QitsEventBus#publish} attempts the
 * PUT inline, never throws, and gives up after the publish timeout — after which the outbox owns
 * delivery. The caller is a run worker, so a qits-events that is down costs one build slot on every
 * finished run those few seconds once and nothing after; see {@link RunAnnouncer}.
 *
 * <p><b>This is where the platform's first automatic causation edge is drawn</b>, and {@link
 * CausingEvent} is the whole of how: the {@code triggerEventId} the port carries is the event that
 * caused the run, read off the run's own row, and it is handed to {@code publish(event, parent)} as
 * an explicit argument. So an event-triggered run's build event names the event that
 * triggered it, and a release train is a chain in the log rather than a set of rows distinguishable
 * from coincidence only by their timestamps.
 *
 * <p><b>It announces every terminal run that says something about its commit, and only those.</b>
 * The caller decides which do — cancelled and superseded rows never reach this port, see {@link
 * RunAnnouncer#onRunFailed} — and {@link SoftwareReleaseAnnouncer} is the other producer on this
 * bus and is additional, never a replacement: a release pipeline's green run publishes {@code
 * BuildSuccessful} and then one {@code SoftwareRelease} per artifact it declared.
 */
@ApplicationScoped
public class BuildAnnouncer implements RunAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onRunSucceeded(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      Instant finishedAt,
      String triggerEventId) {
    bus.publish(
        new BuildSuccessful(
            runId, repoId, projectId, repoName, branch, commitSha, null, wireGating(gating),
            finishedAt),
        CausingEvent.parentOf(triggerEventId, runId));
  }

  @Override
  public void onRunFailed(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      boolean gating,
      String outcome,
      Instant finishedAt,
      String triggerEventId) {
    bus.publish(
        new BuildFailed(
            runId, repoId, projectId, repoName, branch, commitSha, wireGating(gating), outcome,
            finishedAt),
        CausingEvent.parentOf(triggerEventId, runId));
  }

  /**
   * Null means gating on the wire, so every gating build's canonical payload stays byte-identical
   * to what shipped before the field existed; only a non-gating run writes {@code false}.
   */
  private static Boolean wireGating(boolean gating) {
    return gating ? null : Boolean.FALSE;
  }
}
