package eu.wohlben.qits.ci.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A CI run failed: this repository, at this commit, on this branch, finished red at this time —
 * and {@code outcome} says which kind of red.
 *
 * <p>Announced by qits-ci when a run reaches a terminal failure — {@code FAILED}, {@code
 * TIMED_OUT} or {@code CONFIG_ERROR}, and {@code outcome} carries that word verbatim. What it is
 * <b>not</b> announced for is as much of the contract: a {@code CANCELLED} run says nothing (a
 * person withdrew the question), and a run superseded by a newer push says nothing (its row is
 * bookkeeping about the queue, not a fact about the commit). So a subscriber keeping per-commit
 * build status — the reason this event exists — reads every {@code BuildFailed} as a build that
 * genuinely ran, or genuinely could not run, against exactly that commit.
 *
 * <p>The field conventions are {@link BuildSuccessful}'s, stated there at length and only named
 * here: {@code occurredAt} is {@code finishedAt}; {@code eventId} is generated when absent, final
 * once set, and travels in the envelope rather than the payload; {@code repoId} is the storage id
 * and always set, while {@code projectId} and {@code repoName} ride together when the announcing
 * push arrived name-addressed and are omitted from the canonical payload when it did not; {@code
 * gating} is <b>null for a gating run</b> and an explicit {@code false} only for a non-gating one,
 * so absent reads as gating. There is no {@code imageDigest}: a failed run published nothing worth
 * naming.
 */
public record BuildFailed(
    UUID eventId,
    String runId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    Boolean gating,
    String outcome,
    Instant finishedAt)
    implements QitsEvent {

  public BuildFailed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public BuildFailed(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      Boolean gating,
      String outcome,
      Instant finishedAt) {
    this(null, runId, repoId, projectId, repoName, branch, commitSha, gating, outcome, finishedAt);
  }

  @Override
  public Instant occurredAt() {
    return finishedAt;
  }
}
