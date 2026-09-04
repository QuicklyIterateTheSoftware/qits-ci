package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A CI run passed: this repository, at this commit, on this branch, finished green at this time —
 * and, if the pipeline published one, produced this image.
 *
 * <p>Announced by qits-ci when a run reaches {@code SUCCESS}, and the first thing anything on the
 * platform can listen for. It names things the way this platform names things across a boundary: a
 * repository is a String id and a run is a String id, never a reference into another context's
 * tables.
 *
 * <p><b>{@code occurredAt} is {@code finishedAt}</b>, not the moment {@code publish()} was called.
 * The two differ by however long the terminal transition took, and the one that belongs in an event
 * log is when the thing happened.
 *
 * <p><b>{@code eventId} is a component, and that is safe.</b> It is generated when absent and final
 * once set, which gives the stability the idempotent {@code PUT} rests on, and it is kept out of
 * the payload by the library rather than by anything spelled here — {@code CanonicalJson} excludes
 * everything {@link QitsEvent} declares, and this record's accessor is that declaration. So
 * identity travels in the envelope and the payload is the fields below, which is also why reading a
 * payload back yields a fresh id: a received event's identity is the envelope's, and the payload
 * never claimed to carry one.
 *
 * <p>{@code repoId} is the storage id and is always set; {@code projectId} and {@code repoName} are
 * the public {@code (project, name)} pair a subscriber addresses the repository by. They ride
 * together — a push that arrived name-addressed carries both, an id-addressed one carries neither —
 * so a subscriber that has them names the repository and one that does not falls back to the id
 * exactly as before. The pair was added for a deployer that named an image {@code
 * qits/<repoName>:<sha>} off this event; nothing deploys from a green build any longer, and the
 * fields stay because the reader that replaced it — qits-projects' commit ledger, which the release
 * gate reads — has the same addressing problem.
 *
 * <p>{@code imageDigest}, {@code projectId} and {@code repoName} are nullable — a pipeline that runs
 * tests and publishes nothing is an ordinary green build, and an id-addressed push announces no name
 * — and a null field is omitted from the canonical payload rather than written as an explicit null,
 * so an id-addressed push stays byte-identical on the wire.
 *
 * <p>{@code gating} rides the same convention pointed the other way: <b>null means gating</b> — a
 * red outcome of this pipeline would have stood in the way of releasing the commit — and only a
 * non-gating run (a trigger file saying {@code gating: false}; the userflow pipelines) writes an
 * explicit {@code false}. So every gating build's payload is byte-identical to what shipped before
 * the field existed, and a subscriber reads absent as gating.
 */
public record BuildSuccessful(
    UUID eventId,
    String runId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    String imageDigest,
    Boolean gating,
    Instant finishedAt)
    implements QitsEvent {

  public BuildSuccessful {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public BuildSuccessful(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      String imageDigest,
      Boolean gating,
      Instant finishedAt) {
    this(
        null, runId, repoId, projectId, repoName, branch, commitSha, imageDigest, gating,
        finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
