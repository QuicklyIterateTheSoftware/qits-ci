package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * Which repositories the trigger engine asks about when an event arrives. <b>One method</b>, so that
 * swapping where the answer comes from is one class rather than a refactor — which is not a
 * hypothetical: the swap has happened, and it was one class.
 *
 * <h2>The shipped answer: the git host's listing, unioned with what ci knows</h2>
 *
 * <p>{@link ListedAndKnownCiRepos} is the bean the engine gets. It asks the git host for {@code GET
 * <qits.ci.git-host-url>/git} → {@code {"repositories":[…]}} through the {@link GitHostRepoListing}
 * port, and <b>adds</b> that to {@link KnownCiRepos}' answer — the repo ids on recorded runs.
 *
 * <p><b>Union, not replacement.</b> The listing is one HTTP call away, so an unreachable, failed or
 * malformed listing is a WARN naming the url and an empty contribution: the answer is then the known
 * set alone, which is exactly the behaviour that shipped before the listing existed. A read failure
 * never shrinks the candidate set. The two sources also age in opposite directions — the listing is
 * what the host has now, the known set covers a repository the host has stopped listing but ci still
 * holds a run row for.
 *
 * <h2>What this replaced, and why the old answer is worth remembering</h2>
 *
 * <p>The listing did not exist for the feature's first shape, and enumerating qits-artifacts was
 * refused rather than overlooked: its git host was six smart-HTTP routes under {@code
 * /artifacts/git/}, all addressed by an id the caller already has; {@code /v2/_catalog} answers 404
 * with a comment saying enumeration is what the private posture avoids; the npm search route is
 * refused for the same reason; and {@code GET /artifacts/api/repositories} lists <em>artifact</em>
 * repositories — blob, OCI and npm rows in that service's own table — which a git repository never
 * creates.
 *
 * <p>So the shipped answer was {@link KnownCiRepos} alone, with its cost named rather than hidden: a
 * repository that had never pushed could not event-trigger until it did, which blocked
 * bootstrapping a platform by rerun ({@code POST /ci/api/events/trigger} against repositories seeded
 * straight onto the git host). The git host has since grown the one listing that closes it, and this
 * interface is where it landed — a second implementation, the old one kept as the half of the union
 * that survives the host being unreachable, and nothing in the engine changed.
 */
public interface CiCandidateRepos {

  /**
   * The repositories to evaluate against an arriving event, ascending by storage id. Called once per
   * event on the trigger worker, so it may do IO, but every entry it returns costs a read against
   * the git host.
   *
   * <p><b>The unit is a {@link CiRepoRef}, not an id</b>, because the engine reads each candidate's
   * trigger files off the git host and the public content route is name-addressed. A candidate ci
   * knows only from its own run rows has no name and is read id-addressed, exactly as before.
   */
  List<CiRepoRef> candidates();
}
