package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.error.BadRequestException;

/**
 * Validates the three identifiers that arrive from the event intake before they reach a filesystem
 * path, a {@code git} argv, or a container script. ci's inputs are attacker-reachable by design —
 * the intake sits on the token-free {@code PublicPaths} list and its token is blank in dev — so
 * every one of them is checked here rather than trusted.
 *
 * <p>The runner additionally passes the url and sha as {@code bash} <em>positional arguments</em>
 * rather than interpolating them, so these patterns are defence in depth, not the only guard.
 */
public final class CiIdentifiers {

  /** Same slug the git host accepts for a repo id — no separators, no leading dash. */
  private static final String REPO_ID = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** A hex object id (abbreviated ids are accepted; git resolves them). */
  private static final String SHA = "[0-9a-f]{7,64}";

  /** Conservative subset of valid ref names — enough for real branches, hostile to nothing else. */
  private static final String BRANCH = "[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}";

  private CiIdentifiers() {}

  /**
   * @throws BadRequestException if the repo id could escape a path or a git argv
   */
  public static String requireRepoId(String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID)) {
      throw new BadRequestException("Invalid repository id");
    }
    return repoId;
  }

  /**
   * @throws BadRequestException if the sha is not a plain hex object id
   */
  public static String requireSha(String sha) {
    if (sha == null || !sha.matches(SHA)) {
      throw new BadRequestException("Invalid commit sha");
    }
    return sha;
  }

  /**
   * @throws BadRequestException if the branch is not a plain, non-tricky ref name
   */
  public static String requireBranch(String branch) {
    if (branch == null
        || !branch.matches(BRANCH)
        || branch.contains("..")
        || branch.contains("//")
        || branch.endsWith("/")
        || branch.endsWith(".lock")) {
      throw new BadRequestException("Invalid branch name");
    }
    return branch;
  }
}
