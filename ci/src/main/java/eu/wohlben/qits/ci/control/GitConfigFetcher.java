package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
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

  /**
   * How many times one lookup runs the fetch when the tracking ref is contended (see {@link
   * #fetchBranch}). Package-visible so the retry test asserts the bound rather than restating it.
   */
  static final int FETCH_ATTEMPTS = 3;

  /** Base pause between contended fetch attempts; attempt {@code n} waits {@code n} times this. */
  private static final long FETCH_RETRY_BASE_MS = 200;

  /**
   * Runs between contended fetch attempts, given the attempt number that just failed. A field so
   * tests stage the race deterministically (release the ref lock here, count the retries) instead
   * of sleeping against a clock; production keeps the default backoff.
   */
  IntConsumer fetchRetryDelay =
      attempt -> {
        try {
          Thread.sleep(FETCH_RETRY_BASE_MS * attempt);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };

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
    switch (fetchBranch(cache, repoId, branch, localRef, true)) {
      case CONTENDED -> {
        return ConfigLookup.contended();
      }
      case UNREACHABLE -> {
        return ConfigLookup.unreachable();
      }
      case FETCHED -> {
        /* fall through to the reachability check */
      }
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
    // CONTENDED folds into unreachable here on purpose: a candidate that could not be evaluated
    // right now is this path's ordinary answer, and no accepted run hangs on it. The bounded retry
    // above still ran, so reaching this is already the rare case.
    if (fetchBranch(cache, repoId, branch, localRef, false) != FetchOutcome.FETCHED) {
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

  /** What one {@link #fetchBranch} came back with — three outcomes, because two failures differ. */
  enum FetchOutcome {
    /** The tracking ref holds the branch's current tip. */
    FETCHED,
    /** The remote could not be read — host down, repository gone, no such branch. */
    UNREACHABLE,
    /** The local tracking ref stayed contended past {@link #FETCH_ATTEMPTS} (see below). */
    CONTENDED
  }

  /**
   * Fetches the branch's current tip into a ci-private local ref (forced — branches move). The
   * remote is {@code <qits.ci.git-host-url>/git/<repoId>}: {@code /git} is the codebase's
   * second-level segment for the git wire protocol and belongs here, while the configured base
   * names only which service hosts it — qits-artifacts, under its gateway segment, so the fetch
   * lands on {@code /artifacts/git/<repoId>}.
   *
   * <p><b>A failed ref update is retried; a failed remote is not.</b> Two workers share each bare
   * cache — {@code ci-run-worker} fetches for a push while {@code ci-trigger-worker} fetches the
   * same repository for an arriving event — and git updates {@code refs/qits-ci/<branch>} with a
   * compare-and-swap, so the loser of that race fails locally ({@code fetching ref … failed:
   * incorrect old value provided} on git ≥ 2.49's batched updates; {@code cannot lock ref} on older
   * gits) with the host never at fault. Measured live 2026-08-01: a release push landing during a
   * trigger evaluation of the same repository lost its fetch to exactly this. The retry re-runs the
   * whole fetch, which re-reads the old value; {@link #FETCH_ATTEMPTS} bounds it, and exhaustion is
   * {@link FetchOutcome#CONTENDED} so the caller can keep the run rather than treat a local race as
   * an unreachable host.
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
  private FetchOutcome fetchBranch(
      Path cache, String repoId, String branch, String localRef, boolean expected) {
    String remote = gitHostUrl.replaceAll("/+$", "") + "/git/" + repoId;
    List<String> command =
        List.of("git", "fetch", "-q", "--no-tags", remote, "+refs/heads/" + branch + ":" + localRef);
    for (int attempt = 1; ; attempt++) {
      CiProcess.Result fetch = runFetch(cache, command);
      if (fetch.exitCode() == 0) {
        return FetchOutcome.FETCHED;
      }
      if (!isTrackingRefContention(fetch.output())) {
        if (expected) {
          LOG.warnf("ci fetch of %s from %s failed: %s", branch, remote, fetch.output());
        } else {
          LOG.debugf("ci fetch of %s from %s failed: %s", branch, remote, fetch.output());
        }
        return FetchOutcome.UNREACHABLE;
      }
      if (attempt >= FETCH_ATTEMPTS) {
        LOG.warnf(
            "ci fetch of %s from %s lost the tracking ref %d times: %s",
            branch, remote, attempt, fetch.output());
        return FetchOutcome.CONTENDED;
      }
      LOG.debugf(
          "ci fetch of %s from %s raced the tracking ref (attempt %d of %d) — retrying: %s",
          branch, remote, attempt, FETCH_ATTEMPTS, fetch.output());
      fetchRetryDelay.accept(attempt);
    }
  }

  /**
   * Whether a failed fetch lost a race on the <b>local</b> tracking ref rather than failing to read
   * the remote. Matched on git's own wording, both generations of it: batched ref updates (git ≥
   * 2.49) report {@code fetching ref <ref> failed: <reason>} for every transaction failure —
   * {@code incorrect old value provided} is the measured live case — and older gits say {@code
   * cannot lock ref} / {@code unable to update local ref}. Remote failures ({@code does not appear
   * to be a git repository}, curl errors, a missing branch) match none of these.
   */
  static boolean isTrackingRefContention(String output) {
    if (output == null) {
      return false;
    }
    String text = output.toLowerCase(Locale.ROOT);
    return (text.contains("fetching ref ") && text.contains(" failed"))
        || text.contains("cannot lock ref")
        || text.contains("unable to update local ref");
  }

  /** The one process call {@link #fetchBranch} makes — a seam so tests fake a lost CAS. */
  CiProcess.Result runFetch(Path cache, List<String> command) {
    return CiProcess.run(cache, command, GIT_TIMEOUT, MAX_GIT_OUTPUT_CHARS);
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
