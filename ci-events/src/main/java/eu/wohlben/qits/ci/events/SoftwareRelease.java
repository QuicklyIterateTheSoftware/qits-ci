package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An artifact is published: this repository's release pipeline went green and this exact package, at
 * this exact version, is in qits-artifacts and can be consumed.
 *
 * <p><b>It means the package exists, and that is the whole point of the class.</b> The event that
 * says "source control has this release" is qits-workspaces' {@code SCMRelease}, published the
 * moment the release push is accepted; between the two sits the repository's own release pipeline,
 * which checks out the tag, builds and publishes. A downstream bump that triggers on this one can
 * {@code npm ci} immediately, which under the previous single-event design was true only by timing.
 *
 * <p><b>One event per declared artifact.</b> A repository that publishes three packages emits three
 * of these, all under the same triggering event as their {@code parentId} — the fan-out is safe
 * because the outbox enqueues one row per event in its own transaction and {@code
 * CausationScope.current()} is a non-consuming read, so N siblings under one parent is an ordinary
 * shape rather than a special case.
 *
 * <p><b>What is deliberately not here.</b> No {@code projectId} — qits-ci never learns one, and a
 * field it would have to invent is worse than a field a consumer looks up. No {@code branch} — a
 * release is a tag, and the branch it was cut from is the SCM event's business. No registry host on
 * a docker {@code packageName}: the registry is {@code qits-artifacts:8080} inside a step container
 * and {@code localhost:8081} to qits-ci and qits-cd, so no qualified reference is portable and the
 * name travels unqualified ({@code qits/qits-stt}) for the consumer to qualify.
 *
 * <p><b>{@code packageType} is a plain String and its values are {@code npm}, {@code maven}, {@code
 * docker} and {@code daemon}</b> — the last being a platform daemon binary such as {@code
 * qits-ci-daemon}, which qits-artifacts holds and the platform downloads and runs —
 * spelled where a repository writes them — {@code CiArtifact.Type} in the {@code ci} module — rather
 * than a second time here as an enum. The declared keyword <em>is</em> the wire value, so one
 * vocabulary cannot drift from the other; this module depends on the event bus and on nothing else,
 * which is what keeps it the platform's vocabulary rather than qits-ci's internals.
 *
 * <p><b>{@code occurredAt} is an ordinary record component and stays out of the payload anyway</b> —
 * {@code CanonicalJson} excludes everything {@link QitsEvent} declares, so no {@code @JsonIgnore} is
 * spelled here. It is the run's own terminal timestamp: what happened is the pipeline finishing, not
 * the announcement being made.
 */
public record SoftwareRelease(
    UUID eventId,
    String repository,
    String version,
    String packageType,
    String packageName,
    Instant occurredAt)
    implements QitsEvent {

  public SoftwareRelease {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public SoftwareRelease(
      String repository,
      String version,
      String packageType,
      String packageName,
      Instant occurredAt) {
    this(null, repository, version, packageType, packageName, occurredAt);
  }
}
