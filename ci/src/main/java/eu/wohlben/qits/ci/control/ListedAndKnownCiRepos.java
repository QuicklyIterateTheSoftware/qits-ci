package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shipped {@link CiCandidateRepos}: the <b>union</b> of the git host's own repository listing
 * ({@link GitHostRepoListing}) and everything qits-ci already knows ({@link KnownCiRepos}).
 *
 * <p>This is the swap {@link CiCandidateRepos} was designed for, and the union is the whole of it —
 * the listing is <em>added to</em> the known set, never substituted for it. Two reasons, and both
 * are about a read that can fail:
 *
 * <ul>
 *   <li><b>A read failure must not shrink the candidate set.</b> The listing is one HTTP call away
 *       and the trigger engine has to keep working when that call does not answer. An unreachable,
 *       failed or malformed listing is a WARN from the implementation and an empty set here, so the
 *       answer falls back to exactly the behaviour this platform shipped before the listing existed.
 *   <li><b>The two sources age differently</b>, the same argument {@link KnownCiRepos} makes about
 *       its own two. The listing is what the git host has now; the known set is what qits-ci has ever
 *       fetched or recorded, which still covers a repository the host has stopped listing but ci
 *       holds a cache for.
 * </ul>
 *
 * <p>It is a plain bean and {@link KnownCiRepos} is {@code @DefaultBean}, which is what makes this
 * the bean {@code CiEventTriggerService} gets while {@code KnownCiRepos} stays injectable here by
 * its own type. A {@code @Mock} alternative — {@code FakeCandidateRepos} — still outranks both, so
 * the suite's swap of the whole seam is unchanged.
 */
@ApplicationScoped
public class ListedAndKnownCiRepos implements CiCandidateRepos {

  @Inject KnownCiRepos known;

  /**
   * An {@code Instance} rather than a direct injection point because the port ships with no
   * implementation in this module — the HTTP one lives in {@code service/…/githost/}, and a
   * deployment or a suite without it answers from the known set alone.
   */
  @Inject Instance<GitHostRepoListing> listing;

  @Override
  public Set<String> candidates() {
    Set<String> ids = new TreeSet<>(known.candidates());
    if (listing.isResolvable()) {
      ids.addAll(listing.get().repositories());
    }
    return ids;
  }
}
