package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

  /**
   * How many {@code ci-event-*.yml} files one repository may declare. This listing runs per
   * repository per arriving event, so the count is work per frame and it belongs to qits-ci to bound
   * rather than to the repository to choose.
   */
  private static final int MAX_TRIGGER_FILES = 32;

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
    if (!fetchBranch(cache, repoId, branch, localRef, true)) {
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

  /**
   * The event half: fetch the branch, resolve its head, and read every {@code
   * .config/qits/ci-event-*.yml} the tree at that head carries.
   *
   * <p>It rides the <b>same</b> fetch-into-the-bare-cache machinery {@link #read} uses, including
   * {@link CiIdentifiers}' validation, and adds one {@code git ls-tree} of the config directory. No
   * reachability check: an event names no commit, so there is nothing to have been force-pushed away
   * — the head is whatever the branch is now, and that is what the run records.
   *
   * <p><b>Two bounds, both because this walks another repository's tree.</b> A file whose name is not
   * a plain slug is not a trigger file ({@link CiEventTriggerParser#isTriggerPath}) — the name comes
   * back from {@code ls-tree} and goes straight into a {@code git show} argv — and a repository may
   * declare at most {@link #MAX_TRIGGER_FILES} of them, because this runs per repository per arriving
   * event and an unbounded count is an unbounded amount of work per frame.
   */
  @Override
  public EventTriggerLookup readEventTriggers(String repoId, String branch) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);

    Path cache = Path.of(dataDir, "repos", repoId + ".git");
    if (!ensureCache(cache)) {
      return EventTriggerLookup.unreachable();
    }
    String localRef = "refs/qits-ci/" + branch;
    if (!fetchBranch(cache, repoId, branch, localRef, false)) {
      return EventTriggerLookup.unreachable();
    }
    CiProcess.Result head =
        CiProcess.run(
            cache, List.of("git", "rev-parse", localRef), GIT_TIMEOUT, MAX_GIT_OUTPUT_CHARS);
    if (head.exitCode() != 0) {
      LOG.debugf("ci could not resolve %s in %s: %s", localRef, repoId, head.output());
      return EventTriggerLookup.unreachable();
    }
    String headSha = head.output() == null ? "" : head.output().strip();
    if (headSha.isEmpty()) {
      return EventTriggerLookup.unreachable();
    }

    CiProcess.Result listed =
        CiProcess.run(
            cache,
            List.of(
                "git",
                "ls-tree",
                "--name-only",
                headSha,
                CiEventTriggerParser.CONFIG_DIR),
            GIT_TIMEOUT,
            MAX_GIT_OUTPUT_CHARS);
    if (listed.exitCode() != 0) {
      // No such directory in this tree is the ordinary case for a repository that has no qits
      // config at all; git reports it as a failure with nothing on stdout.
      return EventTriggerLookup.found(headSha, List.of());
    }

    List<EventTriggerFile> files = new ArrayList<>();
    for (String line : (listed.output() == null ? "" : listed.output()).split("\\R")) {
      String path = line.strip();
      if (!CiEventTriggerParser.isTriggerPath(path)) {
        continue;
      }
      if (files.size() >= MAX_TRIGGER_FILES) {
        LOG.warnf(
            "%s declares more than %d event triggers — reading the first %d",
            repoId, MAX_TRIGGER_FILES, MAX_TRIGGER_FILES);
        break;
      }
      CiProcess.Result show =
          CiProcess.run(
              cache,
              List.of("git", "show", headSha + ":" + path),
              GIT_TIMEOUT,
              MAX_CONFIG_CHARS);
      if (show.exitCode() != 0) {
        LOG.warnf("ci could not read %s at %s in %s", path, headSha, repoId);
        continue;
      }
      if (show.truncated()) {
        // Loud rather than parsed: half a selection is a different selection.
        LOG.warnf(
            "%s in %s is larger than %d characters — not read as a trigger",
            path, repoId, MAX_CONFIG_CHARS);
        continue;
      }
      files.add(new EventTriggerFile(path, show.output()));
    }
    return EventTriggerLookup.found(headSha, files);
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

  /**
   * Fetches the branch's current tip into a ci-private local ref (forced — branches move). The
   * remote is {@code <qits.ci.git-host-url>/git/<repoId>}: {@code /git} is the codebase's
   * second-level segment for the git wire protocol and belongs here, while the configured base
   * names only which service hosts it — qits-artifacts, under its gateway segment, so the fetch
   * lands on {@code /artifacts/git/<repoId>}.
   *
   * <p><b>{@code expected} is the log level, and it is a parameter because the same failure means
   * two different things.</b> On the push path a fetch that fails is a surprise worth a WARN: a
   * post-receive event named a repository and a branch, so both existed a moment ago and something
   * is wrong. On the trigger-listing path it is <em>routine</em> — that path asks every repository
   * qits-ci has ever heard of, on every arriving event, and a repository that has since been deleted
   * or renamed is simply not a candidate. Measured on the first deployment of the trigger engine: one
   * WARN naming a long-gone repository, per green build, platform-wide, forever. A warning that
   * cannot be acted on and never stops is how a log stops being read.
   */
  private boolean fetchBranch(
      Path cache, String repoId, String branch, String localRef, boolean expected) {
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
      if (expected) {
        LOG.warnf("ci fetch of %s from %s failed: %s", branch, remote, fetch.output());
      } else {
        LOG.debugf("ci fetch of %s from %s failed: %s", branch, remote, fetch.output());
      }
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
