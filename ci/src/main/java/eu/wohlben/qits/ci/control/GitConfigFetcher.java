package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reads the pipeline config from the pushed commit by shelling ci's <b>own</b> {@code git} against
 * the git host's smart-HTTP URL — never the bare origins on disk (an extracted ci service has no
 * shared filesystem with qits). Each repository gets a persistent bare cache under {@code
 * <data-dir>/repos/<repoId>.git}.
 *
 * <p>The fetch asks for the <b>branch ref</b>, not the bare sha: an unadvertised-object fetch would
 * require relaxing the git host's want policy for every (unauthenticated) client, which is a
 * reachability-walk DoS surface. Fetching the ref and then verifying the pushed sha is still an
 * ancestor of it covers the normal case (a later push advanced the branch — the pushed commit is
 * still reachable, CI still runs for it) and correctly reports {@link ConfigLookup#gone()} when a
 * force-push replaced the commit, so nothing is recorded for a push that no longer exists.
 *
 * <p>All three identifiers are validated by {@link CiIdentifiers} before they reach a path or an
 * argv, since the intake that supplies them is reachable without a session.
 */
@ApplicationScoped
public class GitConfigFetcher implements CiConfigSource {

  private static final Logger LOG = Logger.getLogger(GitConfigFetcher.class);

  /**
   * Host-side git calls are short (a small fetch, a blob read) — bound them well below a step's.
   */
  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);

  /** A config file larger than this is not a config file; refuse it rather than parse a tail. */
  private static final int MAX_CONFIG_CHARS = 1024 * 1024;

  /** Enough for any git error message we log. */
  private static final int MAX_GIT_OUTPUT_CHARS = 64 * 1024;

  @ConfigProperty(name = "qits.ci.data-dir")
  String dataDir;

  @ConfigProperty(name = "qits.ci.git-host-url")
  String gitHostUrl;

  @Override
  public ConfigLookup read(String repoId, String branch, String sha) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);
    CiIdentifiers.requireSha(sha);

    Path cache = Path.of(dataDir, "repos", repoId + ".git");
    if (!ensureCache(cache)) {
      return ConfigLookup.unreachable();
    }
    String localRef = "refs/qits-ci/" + branch;
    if (!fetchBranch(cache, repoId, branch, localRef)) {
      return ConfigLookup.unreachable();
    }
    if (!isReachable(cache, sha, localRef)) {
      return ConfigLookup.gone();
    }
    CiProcess.Result show =
        CiProcess.run(
            cache,
            List.of("git", "show", sha + ":" + CiConfigParser.CONFIG_PATH),
            GIT_TIMEOUT,
            MAX_CONFIG_CHARS);
    if (show.exitCode() != 0) {
      return ConfigLookup.absent();
    }
    if (show.truncated()) {
      return ConfigLookup.invalid(
          CiConfigParser.CONFIG_PATH + " is larger than " + MAX_CONFIG_CHARS + " characters");
    }
    return ConfigLookup.found(show.output());
  }

  /** Initializes the per-repo bare cache on first use. */
  private boolean ensureCache(Path cache) {
    if (Files.isDirectory(cache)) {
      return true;
    }
    try {
      Files.createDirectories(cache.getParent());
    } catch (Exception e) {
      LOG.warnf(e, "Could not create ci git cache dir %s", cache.getParent());
      return false;
    }
    CiProcess.Result init =
        CiProcess.run(
            null,
            List.of("git", "init", "-q", "--bare", cache.toString()),
            GIT_TIMEOUT,
            MAX_GIT_OUTPUT_CHARS);
    if (init.exitCode() != 0) {
      LOG.warnf("git init of ci cache %s failed: %s", cache, init.output());
      return false;
    }
    return true;
  }

  /** Fetches the branch's current tip into a ci-private local ref (forced — branches move). */
  private boolean fetchBranch(Path cache, String repoId, String branch, String localRef) {
    String remote = gitHostUrl.replaceAll("/+$", "") + "/git/" + repoId;
    CiProcess.Result fetch =
        CiProcess.run(
            cache,
            List.of(
                "git",
                "fetch",
                "-q",
                "--no-tags",
                remote,
                "+refs/heads/" + branch + ":" + localRef),
            GIT_TIMEOUT,
            MAX_GIT_OUTPUT_CHARS);
    if (fetch.exitCode() != 0) {
      LOG.warnf("ci fetch of %s from %s failed: %s", branch, remote, fetch.output());
      return false;
    }
    return true;
  }

  /** True when {@code sha} is still reachable from the freshly fetched branch tip. */
  private boolean isReachable(Path cache, String sha, String localRef) {
    if (CiProcess.run(
                cache,
                List.of("git", "cat-file", "-e", sha + "^{commit}"),
                GIT_TIMEOUT,
                MAX_GIT_OUTPUT_CHARS)
            .exitCode()
        != 0) {
      return false;
    }
    return CiProcess.run(
                cache,
                List.of("git", "merge-base", "--is-ancestor", sha, localRef),
                GIT_TIMEOUT,
                MAX_GIT_OUTPUT_CHARS)
            .exitCode()
        == 0;
  }
}
