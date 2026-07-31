package eu.wohlben.qits.ci.control;

import java.util.Set;

/**
 * Which repositories the trigger engine asks about when an event arrives. <b>One method</b>, so that
 * swapping where the answer comes from is one class rather than a refactor — which matters, because
 * the answer this platform ships is a fallback.
 *
 * <h2>What the git host offers, checked rather than assumed</h2>
 *
 * <p>The plan's first choice was to enumerate through qits-artifacts, which owns the repositories.
 * <b>It exposes no such surface, and that is a decision rather than a gap.</b> Its git host is six
 * smart-HTTP routes under {@code /artifacts/git/}, all of them addressed by an id the caller already
 * has; there is no bare-directory listing; {@code /v2/_catalog} answers 404 with a comment saying
 * enumeration is what the private posture avoids; the npm search route is refused for the same
 * reason; and {@code GET /artifacts/api/repositories} lists <em>artifact</em> repositories — blob,
 * OCI and npm rows in that service's own table — which a git repository never creates. qits-projects'
 * {@code RepositoryDiscoveryService} does enumerate, and it does so by listing a shared filesystem
 * volume, which is precisely the coupling qits-ci does not have (it keeps its own bare caches and
 * fetches over HTTP so it can run on a machine with no filesystem in common with qits).
 *
 * <p>So the shipped answer is {@link KnownCiRepos}: <b>the repositories qits-ci already knows</b>.
 * The cost is named rather than hidden — a repository that has never pushed since this feature
 * shipped cannot event-trigger until its first push. That is one push, it is the same push that
 * would have been needed to commit the trigger file anyway, and it buys not inventing an
 * enumeration API in another service's private posture.
 *
 * <p>The day qits-artifacts grows a listing, this interface is where it lands: a second
 * implementation, this one deleted or kept as the offline fallback, and nothing in the engine
 * changes.
 */
public interface CiCandidateRepos {

  /**
   * The repository ids to evaluate against an arriving event. Called once per event on the trigger
   * worker, so it may do IO, but every id it returns costs a {@code git fetch}.
   */
  Set<String> candidates();
}
