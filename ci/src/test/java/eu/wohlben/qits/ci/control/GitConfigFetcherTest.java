package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real fetch-into-cache + {@code git show} path with hand-wired fields (no Quarkus):
 * a temp dir laid out as {@code <base>/git/<repoId>} stands in for the git host, addressed over
 * {@code file://} — the same fetch-by-tip-sha mechanics as smart HTTP.
 */
public class GitConfigFetcherTest {

  private static final String BRANCH = "main";

  private Path base;
  private Path dataDir;
  private GitConfigFetcher fetcher;

  @BeforeEach
  void setUp() throws Exception {
    base = Files.createTempDirectory("ci-fetch-host");
    dataDir = Files.createTempDirectory("ci-fetch-data");
    fetcher = new GitConfigFetcher();
    fetcher.dataDir = dataDir.toString();
    fetcher.gitHostUrl = "file://" + base;
  }

  @AfterEach
  void tearDown() throws Exception {
    deleteRecursively(base);
    deleteRecursively(dataDir);
  }

  @Test
  public void findsTheConfigAtThePushedCommit() throws Exception {
    String repoId = "repo-with-config";
    String sha = seedServedRepo(repoId, "steps:\n  - image: alpine:3\n    script: 'true'\n");

    ConfigLookup lookup = fetcher.read(repoId, BRANCH, sha);
    assertEquals(ConfigLookup.Status.FOUND, lookup.status());
    assertEquals("steps:\n  - image: alpine:3\n    script: 'true'\n", lookup.content());
  }

  @Test
  public void commitWithoutTheFileIsAbsent() throws Exception {
    String repoId = "repo-without-config";
    String sha = seedServedRepo(repoId, null);
    assertEquals(ConfigLookup.Status.ABSENT, fetcher.read(repoId, BRANCH, sha).status());
  }

  @Test
  public void unreachableHostIsUnreachable() {
    fetcher.gitHostUrl = "file://" + base.resolve("no-such-dir");
    assertEquals(
        ConfigLookup.Status.UNREACHABLE,
        fetcher.read("any-repo", BRANCH, "0123456789012345678901234567890123456789").status());
  }

  @Test
  public void earlierCommitStillReachableAfterAFastForwardIsFound() throws Exception {
    // A second push advanced the branch before ci ran: the first push's commit is still reachable,
    // so its run must still happen (this is the ordinary racing-push case).
    String repoId = "repo-advanced";
    String firstSha = seedServedRepo(repoId, "steps: []\n");
    advanceServedBranch(repoId, "later.txt");

    ConfigLookup lookup = fetcher.read(repoId, BRANCH, firstSha);
    assertEquals(ConfigLookup.Status.FOUND, lookup.status());
    assertEquals("steps: []\n", lookup.content());
  }

  @Test
  public void commitForcePushedAwayIsGone() throws Exception {
    // The commit no longer exists on the branch — record nothing rather than a red run blaming a
    // commit whose build was never broken.
    String repoId = "repo-forced";
    String orphaned = seedServedRepo(repoId, "steps: []\n");
    assertEquals(ConfigLookup.Status.FOUND, fetcher.read(repoId, BRANCH, orphaned).status());

    replaceServedBranch(repoId, "rewritten.txt");
    assertEquals(ConfigLookup.Status.GONE, fetcher.read(repoId, BRANCH, orphaned).status());
  }

  @Test
  public void hostileIdentifiersAreRejected() {
    // repoId reaches a filesystem path, branch and sha reach a git argv.
    assertThrows(
        BadRequestException.class, () -> fetcher.read("../../etc", BRANCH, "cafebabe0000"));
    assertThrows(
        BadRequestException.class, () -> fetcher.read("repo-1", "--upload-pack=x", "cafebabe0000"));
    assertThrows(BadRequestException.class, () -> fetcher.read("repo-1", BRANCH, "not-a-sha"));
    assertThrows(
        BadRequestException.class, () -> fetcher.read("repo-1", "a/../../b", "cafebabe0000"));
  }

  // --- the tracking-ref race: a local CAS lost to this process's own other worker ---

  @Test
  public void aLostTrackingRefRaceIsRetriedAndTheFetchThatLandsFindsTheConfig() throws Exception {
    // Two workers share the bare cache, so one lookup can lose refs/qits-ci/<branch> to the other.
    // The loser re-runs the whole fetch — and must then read the config exactly as if nothing raced.
    String repoId = "repo-raced";
    String sha = seedServedRepo(repoId, "steps:\n  - image: alpine:3\n    script: 'true'\n");
    ScriptedFetcher raced = scriptedFetcher(contentionFailure(), contentionFailure());
    List<Integer> backoffs = new ArrayList<>();
    raced.fetchRetryDelay = backoffs::add;

    ConfigLookup lookup = raced.read(repoId, BRANCH, sha);

    assertEquals(ConfigLookup.Status.FOUND, lookup.status());
    assertEquals(3, raced.attempts, "two lost races, then the fetch that lands");
    assertEquals(List.of(1, 2), backoffs, "the backoff runs between attempts, never after the last");
  }

  @Test
  public void aTrackingRefThatStaysContendedPastTheBoundIsContendedNeverUnreachable()
      throws Exception {
    // The bound is the point: past it the caller must hear CONTENDED — a local race, so the run
    // survives — where UNREACHABLE would discard the run as if the host could not be asked.
    String repoId = "repo-held";
    String sha = seedServedRepo(repoId, "steps: []\n");
    ScriptedFetcher held = scriptedFetcher(contendedEveryTime());

    assertEquals(ConfigLookup.Status.CONTENDED, held.read(repoId, BRANCH, sha).status());
    assertEquals(
        GitConfigFetcher.FETCH_ATTEMPTS,
        held.attempts,
        "the retry is bounded, and this is the bound");
  }

  @Test
  public void aRemoteFailureIsNotRetriedAndStaysUnreachable() throws Exception {
    // The repository is right there and serving: if the remote failure were wrongly retried, the
    // next attempt would land and this read would come back FOUND.
    String repoId = "repo-down-once";
    String sha = seedServedRepo(repoId, "steps: []\n");
    ScriptedFetcher down =
        scriptedFetcher(
            new CiProcess.Result(
                128,
                "fatal: 'file:///x/git/repo-down-once' does not appear to be a git repository",
                false,
                false));

    assertEquals(ConfigLookup.Status.UNREACHABLE, down.read(repoId, BRANCH, sha).status());
    assertEquals(1, down.attempts, "only the local race earns a retry");
  }

  @Test
  public void theTriggerListingFoldsExhaustedContentionIntoUnreachable() throws Exception {
    // No accepted run hangs on a trigger evaluation, so this path keeps its ordinary "could not
    // evaluate right now" answer — the bounded retry has still already run to get here.
    String repoId = "repo-trigger-raced";
    seedServedRepo(repoId, "steps: []\n", Map.of(".config/qits/ci-event-a.yml", "event: A\n"));

    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        scriptedFetcher(contendedEveryTime()).readEventTriggers(repoId, BRANCH).status());
  }

  @Test
  public void contentionIsRecognisedInGitsOwnWordingAcrossGenerations() {
    // The measured live case: git ≥ 2.49's batched ref updates report every transaction failure
    // this way.
    assertTrue(
        GitConfigFetcher.isTrackingRefContention(
            "error: fetching ref refs/qits-ci/main failed: incorrect old value provided"));
    // Older gits, one ref at a time.
    assertTrue(
        GitConfigFetcher.isTrackingRefContention(
            "error: cannot lock ref 'refs/qits-ci/main': reference already exists"));
    assertTrue(GitConfigFetcher.isTrackingRefContention("error: unable to update local ref"));
    // Remote failures match none of these — they are UNREACHABLE, and must never be retried as a
    // local race.
    assertFalse(
        GitConfigFetcher.isTrackingRefContention(
            "fatal: 'http://host/git/repo' does not appear to be a git repository"));
    assertFalse(
        GitConfigFetcher.isTrackingRefContention("fatal: couldn't find remote ref refs/heads/main"));
    assertFalse(GitConfigFetcher.isTrackingRefContention(null));
  }

  // --- the event half: listing .config/qits/ci-event-*.yml at the branch head ---

  @Test
  public void listsEveryTriggerFileAtTheBranchHeadWithTheShaItReadThemAt() throws Exception {
    String repoId = "repo-with-triggers";
    String sha =
        seedServedRepo(
            repoId,
            "steps: []\n",
            Map.of(
                ".config/qits/ci-event-one.yml", "event: A\n",
                ".config/qits/ci-event-two.yml", "event: B\n"));

    EventTriggerLookup lookup = fetcher.readEventTriggers(repoId, BRANCH);
    assertEquals(EventTriggerLookup.Status.FOUND, lookup.status());
    assertEquals(sha, lookup.headSha(), "the run records the head the trigger was read at");
    assertEquals(
        Map.of(".config/qits/ci-event-one.yml", "event: A\n", ".config/qits/ci-event-two.yml", "event: B\n"),
        lookup.files().stream()
            .collect(Collectors.toMap(EventTriggerFile::path, EventTriggerFile::content)));
  }

  @Test
  public void thePostReceiveConfigAndUnrelatedFilesAreNotTriggerFiles() throws Exception {
    String repoId = "repo-mixed";
    seedServedRepo(
        repoId,
        "steps: []\n",
        Map.of(
            ".config/qits/ci-event-real.yml", "event: A\n",
            ".config/qits/notes.md", "hello\n",
            ".config/qits/ci-event-nope.yaml", "event: B\n"));

    List<String> paths =
        fetcher.readEventTriggers(repoId, BRANCH).files().stream()
            .map(EventTriggerFile::path)
            .toList();
    assertEquals(List.of(".config/qits/ci-event-real.yml"), paths);
  }

  @Test
  public void aRepositoryWithNoQitsConfigAtAllListsNothingRatherThanFailing() throws Exception {
    String repoId = "repo-bare";
    String sha = seedServedRepo(repoId, null);
    EventTriggerLookup lookup = fetcher.readEventTriggers(repoId, BRANCH);
    assertEquals(EventTriggerLookup.Status.FOUND, lookup.status());
    assertEquals(sha, lookup.headSha());
    assertEquals(List.of(), lookup.files());
  }

  @Test
  public void theListingFollowsTheBranchRatherThanAPinnedCommit() throws Exception {
    // An event names no commit, so the head is resolved rather than given — and it must be the head
    // as of this read, not whatever it was the last time ci fetched.
    String repoId = "repo-moving";
    String first = seedServedRepo(repoId, "steps: []\n", Map.of(".config/qits/ci-event-a.yml", "event: A\n"));
    assertEquals(first, fetcher.readEventTriggers(repoId, BRANCH).headSha());

    advanceServedBranch(repoId, "later.txt");
    String moved = fetcher.readEventTriggers(repoId, BRANCH).headSha();
    assertNotEquals(first, moved);
  }

  @Test
  public void anUnreachableOrUnknownRepositoryIsUnreachableRatherThanEmpty() {
    // Told apart from "declares no trigger", because the two mean different things to the engine:
    // one is a repository with nothing to say, the other is a repository ci could not ask.
    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        fetcher.readEventTriggers("no-such-repo", BRANCH).status());
    fetcher.gitHostUrl = "file://" + base.resolve("no-such-dir");
    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        fetcher.readEventTriggers("any-repo", BRANCH).status());
  }

  @Test
  public void aRepositoryWithoutThatBranchIsUnreachable() throws Exception {
    String repoId = "repo-other-branch";
    seedServedRepo(repoId, "steps: []\n");
    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        fetcher.readEventTriggers(repoId, "no-such-branch").status());
  }

  @Test
  public void theListingValidatesItsIdentifiersToo() {
    // Same standard as read(): repoId reaches a filesystem path, branch reaches a git argv.
    assertThrows(BadRequestException.class, () -> fetcher.readEventTriggers("../../etc", BRANCH));
    assertThrows(
        BadRequestException.class, () -> fetcher.readEventTriggers("repo-1", "--upload-pack=x"));
  }

  /** The measured live wording of a lost tracking-ref race, as one failed fetch result. */
  private static CiProcess.Result contentionFailure() {
    return new CiProcess.Result(
        1,
        "error: fetching ref refs/qits-ci/main failed: incorrect old value provided",
        false,
        false);
  }

  /** One staged contention failure per attempt the bound allows — the race that never clears. */
  private static CiProcess.Result[] contendedEveryTime() {
    CiProcess.Result[] failures = new CiProcess.Result[GitConfigFetcher.FETCH_ATTEMPTS];
    Arrays.fill(failures, contentionFailure());
    return failures;
  }

  /**
   * A fetcher wired like {@link #setUp}'s, but with its fetch shell-out scripted: the staged
   * failures answer first, and once they run out the fetch really runs. The race is staged, the git
   * is real — so a retry that lands is a fetch that landed.
   */
  private ScriptedFetcher scriptedFetcher(CiProcess.Result... staged) {
    ScriptedFetcher scripted = new ScriptedFetcher(staged);
    scripted.dataDir = dataDir.toString();
    scripted.gitHostUrl = "file://" + base;
    return scripted;
  }

  private static final class ScriptedFetcher extends GitConfigFetcher {
    private final Queue<CiProcess.Result> staged;
    private int attempts;

    ScriptedFetcher(CiProcess.Result... staged) {
      this.staged = new ArrayDeque<>(List.of(staged));
    }

    @Override
    CiProcess.Result runFetch(Path cache, List<String> command) {
      attempts++;
      CiProcess.Result failure = staged.poll();
      return failure != null ? failure : super.runFetch(cache, command);
    }
  }

  /**
   * Creates a bare repo at {@code <base>/git/<repoId>} whose tip commit carries the config content
   * (or no config file at all for {@code null}); returns the tip sha.
   */
  private String seedServedRepo(String repoId, String configContent) throws Exception {
    return seedServedRepo(repoId, configContent, Map.of());
  }

  private String seedServedRepo(String repoId, String configContent, Map<String, String> extraFiles)
      throws Exception {
    Path work = Files.createTempDirectory("ci-fetch-work");
    try {
      git(null, "init", "-q", "-b", "main", work.toString());
      Files.writeString(work.resolve("readme.txt"), "hello\n");
      if (configContent != null) {
        Path config = work.resolve(CiConfigParser.CONFIG_PATH);
        Files.createDirectories(config.getParent());
        Files.writeString(config, configContent);
      }
      for (Map.Entry<String, String> extra : extraFiles.entrySet()) {
        Path file = work.resolve(extra.getKey());
        Files.createDirectories(file.getParent());
        Files.writeString(file, extra.getValue());
      }
      git(work, "add", ".");
      git(work, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", "seed");
      String sha = git(work, "rev-parse", "HEAD").trim();
      Path served = base.resolve("git").resolve(repoId);
      Files.createDirectories(served.getParent());
      git(null, "clone", "-q", "--bare", work.toString(), served.toString());
      return sha;
    } finally {
      deleteRecursively(work);
    }
  }

  /** Adds a commit on top of the served branch (a fast-forward second push). */
  private void advanceServedBranch(String repoId, String file) throws Exception {
    Path work = Files.createTempDirectory("ci-fetch-advance");
    try {
      Path served = base.resolve("git").resolve(repoId);
      git(null, "clone", "-q", "-b", BRANCH, served.toString(), work.toString());
      Files.writeString(work.resolve(file), "later\n");
      git(work, "add", ".");
      git(work, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", "later");
      git(work, "push", "-q", "origin", BRANCH);
    } finally {
      deleteRecursively(work);
    }
  }

  /** Rewrites the served branch to an unrelated commit (a force-push that orphans the old tip). */
  private void replaceServedBranch(String repoId, String file) throws Exception {
    Path work = Files.createTempDirectory("ci-fetch-replace");
    try {
      Path served = base.resolve("git").resolve(repoId);
      git(null, "init", "-q", "-b", BRANCH, work.toString());
      Files.writeString(work.resolve(file), "rewritten\n");
      git(work, "add", ".");
      git(
          work,
          "-c",
          "user.email=ci@test",
          "-c",
          "user.name=ci",
          "commit",
          "-q",
          "-m",
          "rewritten");
      git(work, "push", "-q", "--force", served.toString(), BRANCH + ":" + BRANCH);
    } finally {
      deleteRecursively(work);
    }
  }

  private String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }

  private static void deleteRecursively(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
