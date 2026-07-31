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
 * identity travels in the envelope and the payload is the six fields below, which is also why
 * reading a payload back yields a fresh id: a received event's identity is the envelope's, and the
 * payload never claimed to carry one.
 *
 * <p>{@code imageDigest} is nullable — a pipeline that runs tests and publishes nothing is an
 * ordinary green build — and a null field is omitted from the canonical payload rather than written
 * as an explicit null.
 */
public record BuildSuccessful(
    UUID eventId,
    String runId,
    String repoId,
    String branch,
    String commitSha,
    String imageDigest,
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
      String branch,
      String commitSha,
      String imageDigest,
      Instant finishedAt) {
    this(null, runId, repoId, branch, commitSha, imageDigest, finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
