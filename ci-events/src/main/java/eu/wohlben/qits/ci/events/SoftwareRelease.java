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
 * <p><b>{@code projectId}, {@code repoId} and {@code repoName} name the repository the way the
 * platform does, and they are additive.</b> This class used to argue that no {@code projectId}
 * belonged here because "qits-ci never learns one" — which stopped being true when a run started
 * carrying its repository's public coordinate ({@code ci_run.project_id}/{@code repo_name}, the
 * identity campaign's V5). A consumer that has to deploy what was published needs an address for the
 * repository at all, and making it look one up — against qits-projects, on the event's dispatch
 * thread, for a fact the publisher already holds — is a hop that can fail for a release that cannot.
 * So the coordinate rides along:
 *
 * <ul>
 *   <li>{@code repoId} is the git host's storage id of the repository whose pipeline published. It is
 *       the same string {@code repository} has always carried, under the name the rest of the
 *       platform spells it with — {@code repository} is kept exactly as it is, because every
 *       committed selection and every existing consumer reads it, and a field whose meaning depends
 *       on who is reading it is worth less than two fields that each mean one thing.
 *   <li>{@code projectId} is the owning project as qits-projects names it, and it is the genuinely
 *       new fact. <b>Nullable</b>: a run of a repository the candidate listing answered id-addressed
 *       carries none, and {@code CanonicalJson}'s {@code NON_NULL} inclusion then leaves the key out
 *       of the payload entirely rather than writing a null. A consumer that needs it must treat
 *       absence as "ask somebody", never as an id.
 *   <li>{@code repoName} is the repository's public name, and it is the <b>other half of the only
 *       address that works above the projects↔githost seam</b>: content is read
 *       {@code /git/<projectId>/<repoName>/blob/…}, and the id-addressed scheme it falls back to is
 *       guarded — qits-githost's storage-client check refuses it to everyone but qits-projects. So a
 *       deploy consumer reading a released repository's spec with {@code projectId} alone cannot
 *       read it at all. <b>Nullable</b> for exactly {@code projectId}'s reason and spelled the same
 *       way: an id-addressed run has no name, and the key is then absent rather than null.
 * </ul>
 *
 * <p><b>What is deliberately still not here.</b> No {@code branch} — a release is a tag, and the
 * branch it was cut from is the SCM event's business. That is sharper than it used to be rather than
 * softer: the release request's backing branch ({@code release/&lt;id&gt;}) is <em>deleted</em> when
 * the tag is created, so a branch on this event would name a ref that no longer exists by the time
 * anybody read it. No registry host on a docker {@code packageName}: the registry is {@code
 * qits-artifacts:8080} inside a step container and {@code registry.dev.localhost:8080} to qits-ci and
 * qits-cd, so no qualified reference is portable and the name travels unqualified ({@code
 * qits/qits-stt}) for the consumer to qualify.
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
    String projectId,
    String repoId,
    String repoName,
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
      String projectId,
      String repoId,
      String repoName,
      String version,
      String packageType,
      String packageName,
      Instant occurredAt) {
    this(
        null,
        repository,
        projectId,
        repoId,
        repoName,
        version,
        packageType,
        packageName,
        occurredAt);
  }
}
