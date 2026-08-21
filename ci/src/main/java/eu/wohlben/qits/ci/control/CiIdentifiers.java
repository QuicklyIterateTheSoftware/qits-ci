package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.error.BadRequestException;

/**
 * Validates the untrusted strings that reach a filesystem path or an argv. Three — the repo id, the
 * branch and the sha — arrive on an {@code SCMPublishCommit} payload, which says what somebody
 * pushed and is therefore exactly as attacker-shaped as the intake POST it replaced; one, the step's
 * {@code image}, arrives from a file in the repository being tested; and one, the daemon version,
 * arrives in another bus payload. All five are attacker-reachable by design, so all five are checked
 * here rather than trusted.
 *
 * <p>These patterns are <b>defence in depth, not the only guard</b>. Nothing here is ever
 * interpolated into a shell string: the container's whole contract rides as environment and the
 * bootstrap is a constant, so an argv assembled from these values is passed to {@code
 * ProcessBuilder}, which never re-splits it.
 */
public final class CiIdentifiers {

  /** Same slug the git host accepts for a repo id — no separators, no leading dash. */
  private static final String REPO_ID = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /**
   * A project id and a repository name, which are the two halves of the public address {@code
   * /git/<projectId>/<repoName>}. Both are slugs of exactly the shape a repo id is — the same
   * charset, the same bound, the same "no leading dash" — because they land in the same place: one
   * path segment of a URL a step container clones from. Spelled as their own constant rather than
   * reusing {@link #REPO_ID} so that the storage id and the public name can diverge later without a
   * silent widening of either.
   */
  private static final String NAME_SEGMENT = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** A hex object id (abbreviated ids are accepted; git resolves them). */
  private static final String SHA = "[0-9a-f]{7,64}";

  /** Conservative subset of valid ref names — enough for real branches, hostile to nothing else. */
  private static final String BRANCH = "[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}";

  /**
   * One path segment, nothing else — a calver and a digest hex both fit, and neither {@code /},
   * {@code ..}, {@code ?}, {@code #} nor whitespace can, which is what keeps this value from
   * redirecting the download it is interpolated into (ci-daemon-autoadopt-plan.md §1.5).
   */
  private static final String DAEMON_VERSION = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";

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
   * The owning project of a name-addressed repository, when the event or the listing carried one.
   *
   * <p><b>Only when present.</b> The name half of a repository's identity is nullable by design —
   * an id-addressed push announces without it — so absence is the compatibility arm and never a
   * refusal. What is checked is a value that IS there, because it reaches a clone URL as one path
   * segment exactly like the repo id does.
   *
   * @throws BadRequestException if the project id could escape a path
   */
  public static String requireProjectId(String projectId) {
    if (projectId == null || !projectId.matches(NAME_SEGMENT)) {
      throw new BadRequestException("Invalid project id");
    }
    return projectId;
  }

  /**
   * The repository's addressable name within its project — the second segment of {@code
   * /git/<projectId>/<repoName>}. Present-only, for {@link #requireProjectId}'s reason.
   *
   * @throws BadRequestException if the name could escape a path
   */
  public static String requireRepoName(String repoName) {
    if (repoName == null || !repoName.matches(NAME_SEGMENT)) {
      throw new BadRequestException("Invalid repository name");
    }
    return repoName;
  }

  /**
   * Validates a whole reference: the storage id always, and the name pair only when it is there.
   * The one place the "check it when present" rule is written, so no call site has to remember it.
   *
   * @throws BadRequestException if any part that is present could escape a path or an argv
   */
  public static CiRepoRef requireRepo(CiRepoRef repo) {
    if (repo == null) {
      throw new BadRequestException("Invalid repository id");
    }
    requireRepoId(repo.repoId());
    if (repo.projectId() != null) {
      requireProjectId(repo.projectId());
    }
    if (repo.name() != null) {
      requireRepoName(repo.name());
    }
    return repo;
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
   * The step's container image, as declared in the repository's own pipeline config.
   *
   * <p>Deliberately loose: an image reference can carry a registry host, a port, a path, a tag and a
   * digest, and deciding which of those resolve is the registry's job, not this one's. What it must
   * not do is <b>begin with {@code -}</b>. That value is handed to the docker CLI as a positional
   * argument, and while no exploit is known through it — {@code ProcessBuilder} never shell-splits,
   * and the fixed {@code -c <BOOTSTRAP>} tokens that follow defeat the obvious re-parses — "the
   * argument parser will surely never take this for a flag" is not a claim worth defending once a
   * year. Hardening, not a fix for anything demonstrated.
   *
   * @throws BadRequestException if the image is blank or could be read as an option
   */
  public static String requireImage(String image) {
    if (image == null || image.isBlank() || image.startsWith("-")) {
      throw new BadRequestException("Invalid step image");
    }
    return image;
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

  /**
   * A daemon pin candidate's version, as adopted off a {@code SoftwareRelease} bus payload and later
   * interpolated into a URL path segment a step container fetches
   * ({@code qits.ci.daemon-binary-url-template}). Accepts both spellings a pin can legitimately
   * hold: a calver ({@code 2026.803.91607}) and a sha256 digest hex.
   *
   * <p>This is the check that replaces {@code CiDaemonLauncher}'s old boot-time
   * {@code daemonVersionComplaint}, which warned only while the shipped template still spelled
   * {@code sha256:{version}} and went silent by construction once it stopped — see
   * ci-daemon-autoadopt-plan.md §1.5. This one is enforced at adoption rather than merely logged,
   * because the value it guards now arrives over the bus rather than from a reviewed deployment.
   *
   * @throws BadRequestException if the version could redirect the download it is interpolated into
   */
  public static String requireDaemonVersion(String version) {
    if (version == null || !version.matches(DAEMON_VERSION)) {
      throw new BadRequestException("Invalid daemon version");
    }
    return version;
  }
}
