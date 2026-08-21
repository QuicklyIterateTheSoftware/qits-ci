package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * "Every repository the platform holds, with its public name" — qits-projects' catalogue, read once
 * per arriving event by {@link ListedAndKnownCiRepos}.
 *
 * <p>It replaces {@link GitHostRepoListing} as the enumeration the trigger engine prefers, and the
 * reason is the identity cutover rather than taste: the git host's own {@code GET /git} is an
 * internal storage listing that answers opaque UUIDs, and a UUID is not something a trigger file can
 * name or a clone URL can be built from. qits-projects is where the {@code (projectId, name)} pair
 * lives, so that is where the candidate list comes from.
 *
 * <pre>
 *   GET {qits.ci.projects-url}/projects/api/repositories
 *   200 {"repositories":[{"id","projectId","name","mainBranch"}, …]}
 * </pre>
 *
 * <p>An interface rather than a call so {@code ci} stays free of {@code java.net.http} and of
 * another service's wire shape — the same reason {@link CiConfigSource}, {@link GitHostRepoListing}
 * and {@link DaemonReleaseLog} are ports here and hand-rolled clients in {@code service}.
 *
 * <p><b>Two answers, and they mean different things.</b> {@link #configured()} says whether this
 * deployment names a qits-projects at all; {@link #repositories()} says what it holds. An
 * unconfigured listing is not a failed read — it is a deployment that predates the cutover, or a
 * clone-alone build, and the caller falls back to the git host's listing rather than shrinking the
 * candidate set. That is the campaign's standing kill-switch shape.
 */
public interface CiRepositoryListing {

  /**
   * Whether {@code qits.ci.projects-url} is set. False means "ask the git host instead", never "the
   * platform holds nothing".
   */
  boolean configured();

  /**
   * Every repository qits-projects holds, or an <b>empty list</b> when the catalogue could not be
   * read. <b>Never throws</b>: this runs on {@code ci-trigger-worker} in front of the evaluation,
   * and a read failure must not shrink the candidate set below what qits-ci already knows, let alone
   * abort the evaluation. An implementation logs its own WARN and answers empty.
   *
   * <p>An entry whose {@code name} is null is <b>skipped</b> rather than answered id-addressed: with
   * no public address there is no trigger file this engine could read for it, and the id route is
   * qits-projects' own after the cutover. Every value that survives is one {@link CiIdentifiers}
   * accepts, because all three reach a URL.
   */
  List<CiRepoRef> repositories();
}
