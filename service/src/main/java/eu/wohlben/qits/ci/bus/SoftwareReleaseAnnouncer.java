package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.ReleaseAnnouncer;
import eu.wohlben.qits.ci.events.SoftwareRelease;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * Turns one published artifact into the platform's {@code SoftwareRelease} event and hands it to the
 * bus. The {@link ReleaseAnnouncer} port's sole production implementation, and the sibling of {@link
 * BuildAnnouncer} in every structural respect: it lives here because the {@code ci} module
 * knows nothing of the bus, and zero implementations is a supported configuration.
 *
 * <p><b>One artifact, one call, one event.</b> The fan-out is the caller's — {@code CiRunService}
 * calls this once per declaration — so nothing here knows the events are siblings and nothing has to.
 * The bus already supports the shape: the outbox enqueues one row per event in its own transaction,
 * and the parent is an explicit argument rather than an ambient value that a first publish could
 * consume.
 *
 * <p><b>{@code repository} and {@code repoId} are handed the same string, deliberately.</b> The port
 * has always given this method the run's {@code repo_id} — the git host's storage id of the
 * repository whose pipeline published — and that is what {@code repository} has carried on the wire
 * since the event existed. Naming the same value twice is what makes the pair additive: every
 * existing consumer's field keeps its exact bytes, and a consumer that needs to address a repository
 * reads a field whose name says what it holds, instead of guessing whether {@code repository} is an
 * id or a name on the platform it happens to be running on. The genuinely new fact is {@code
 * projectId}, which no consumer could have derived from this event at all.
 *
 * <p><b>qits-ci publishes this name and subscribes to nothing under it.</b> The wire name is the
 * simple class name, and qits-workspaces is simultaneously renaming <em>its</em> release event
 * {@code SoftwareRelease → SCMRelease} — the two halves of one cutover, after which this is the only
 * producer of the name on the platform. A repository that wants to hear it declares a trigger file;
 * this service listens for nothing new.
 */
@ApplicationScoped
public class SoftwareReleaseAnnouncer implements ReleaseAnnouncer {

  @Inject QitsEventBus bus;

  @Override
  public void onArtifactPublished(
      String runId,
      String repoId,
      String projectId,
      String version,
      String packageType,
      String packageName,
      Instant finishedAt,
      String triggerEventId) {
    bus.publish(
        new SoftwareRelease(
            repoId, projectId, repoId, version, packageType, packageName, finishedAt),
        CausingEvent.parentOf(triggerEventId, runId));
  }
}
