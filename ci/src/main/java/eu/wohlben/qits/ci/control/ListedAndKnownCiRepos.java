package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shipped {@link CiCandidateRepos}: the <b>union</b> of the platform's own repository catalogue
 * and everything qits-ci already knows ({@link KnownCiRepos}).
 *
 * <p>This is the swap {@link CiCandidateRepos} was designed for, and the union is the whole of it —
 * the catalogue is <em>added to</em> the known set, never substituted for it. Two reasons, and both
 * are about a read that can fail:
 *
 * <ul>
 *   <li><b>A read failure must not shrink the candidate set.</b> The catalogue is one HTTP call away
 *       and the trigger engine has to keep working when that call does not answer. An unreachable,
 *       failed or malformed listing is a WARN from the implementation and an empty list here, so the
 *       answer falls back to exactly the behaviour this platform shipped before the listing existed.
 *   <li><b>The two sources age differently</b>, the same argument {@link KnownCiRepos} makes about
 *       its own two. The catalogue is what the platform has now; the known set is what qits-ci has
 *       ever recorded, which still covers a repository the catalogue has stopped listing but ci
 *       holds a run row for.
 * </ul>
 *
 * <h2>Which catalogue, and the kill switch between them</h2>
 *
 * <p>{@link CiRepositoryListing} — qits-projects — is the one that answers <b>names</b>, and after
 * the identity cutover names are the only thing a trigger file can select on or a clone URL can be
 * built from. It is preferred whenever {@code qits.ci.projects-url} is set.
 *
 * <p>{@link GitHostRepoListing} — the git host's own {@code GET /git} — is the fallback for a
 * deployment that names no qits-projects: it answers storage ids, which is what this service used
 * before the campaign and what a pre-cutover platform (where id and name agree) still triggers
 * correctly on. It is also what keeps a clone-alone suite and a standalone run of this repository
 * working. After the cutover that route is qits-projects' alone and answers this caller nothing,
 * which costs the fallback arm nothing it was promising.
 *
 * <p><b>A named entry beats an id-only one for the same repository.</b> The known set contributes
 * ids off run rows, and the catalogue contributes the same repositories with their public
 * coordinate; keyed by storage id, the richer reference wins, so a repository ci has built before is
 * still read name-addressed once the catalogue can say what its name is.
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

  /** The same arrangement, for the catalogue that answers names — {@code service/…/projects/}. */
  @Inject Instance<CiRepositoryListing> projects;

  @Override
  public List<CiRepoRef> candidates() {
    Map<String, CiRepoRef> byId = new TreeMap<>();
    for (CiRepoRef ref : known.candidates()) {
      byId.put(ref.repoId(), ref);
    }
    for (CiRepoRef ref : catalogue()) {
      // Named wins: the catalogue's entry carries the public coordinate the known set cannot.
      byId.merge(ref.repoId(), ref, (existing, arriving) -> arriving.named() ? arriving : existing);
    }
    return List.copyOf(byId.values());
  }

  /**
   * The platform's catalogue: qits-projects when this deployment names one, the git host's storage
   * listing otherwise. Never both — a configured qits-projects is the authority on which
   * repositories exist, and adding the storage listing under it would put UUIDs nothing can address
   * back into the candidate set.
   */
  private List<CiRepoRef> catalogue() {
    if (projects.isResolvable() && projects.get().configured()) {
      return projects.get().repositories();
    }
    if (listing.isResolvable()) {
      return listing.get().repositories().stream().map(CiRepoRef::of).toList();
    }
    return List.of();
  }
}
