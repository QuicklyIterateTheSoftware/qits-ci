package eu.wohlben.qits.ci.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiTriggerScope;
import eu.wohlben.qits.ci.control.CiConfigSource.CommitHeld;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerFile;
import eu.wohlben.qits.ci.control.CiConfigSource.EventTriggerLookup;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpGitConfigSource} on its own — plain JUnit against a real git host on a real socket
 * ({@link StubGitHost}), no Quarkus, since its only collaborators are an {@code ObjectMapper} and a
 * config string. Same shape as {@link HttpGitHostRepoListingTest}, one route over.
 *
 * <p>The repositories are real bares built by real {@code git}, because what is under test is the
 * two halves of a contract with another service: which url each read goes to, and what this class
 * makes of the answer. A commit the branch has moved past is the case worth having in front of you —
 * it is the whole reason the read is addressed by sha.
 */
public class HttpGitConfigSourceTest {

  private static final String BRANCH = "main";

  private Path root;
  private StubGitHost.Server host;
  private HttpGitConfigSource source;

  /** A repository qits-ci knows by storage id alone — the id-addressed arm. */
  private static CiRepoRef id(String repoId) {
    return CiRepoRef.of(repoId);
  }

  @BeforeEach
  void startHost() throws Exception {
    root = Files.createTempDirectory("ci-config-host");
    host = StubGitHost.start(root);
    source = new HttpGitConfigSource();
    source.gitHostUrl = host.gitHostUrl();
    source.objectMapper = new ObjectMapper();
    source.gitHostBearer = () -> java.util.Optional.of("machine-token");
  }

  @AfterEach
  void stopHost() throws Exception {
    host.stop();
    StubGitHost.deleteRecursively(root);
  }

  // --- the commit-held probe: does this repository still hold this sha ---
  //
  // It was `read(repo, branch, sha)` until 2026-09-05, and answered a whole ConfigLookup: the
  // pipeline at a pushed commit, told apart from ABSENT/GONE/UNREACHABLE/INVALID. Per-push CI
  // retired and nothing reads a pipeline out of a commit any more, so what is left is the one
  // question the shared run path still asks — and it is answered by the tree read that used to be
  // the second half of that method.

  @Test
  public void aCommitTheRepositoryHoldsIsHeld() throws Exception {
    String repoId = "repo-with-commit";
    String sha = seed(repoId, "steps: []\n");

    assertEquals(CommitHeld.HELD, source.commitHeld(id(repoId), sha));
  }

  @Test
  public void aCommitTheRepositoryDoesNotHoldIsGone() throws Exception {
    // The 404 that means the rev does not resolve: there is no commit to describe, so a run about it
    // describes a push that no longer exists and is discarded.
    String repoId = "repo-without-that-commit";
    seed(repoId, "steps: []\n");

    assertEquals(
        CommitHeld.GONE,
        source.commitHeld(id(repoId), "0123456789012345678901234567890123456789"));
  }

  @Test
  public void aCommitTheBranchHasMovedPastIsStillHeld() throws Exception {
    // HELD is about the repository holding the object, never about the branch pointing at it — the
    // narrowing this answer took on when it stopped meaning "reachable from the tip". A commit a
    // second push moved past is still a commit a run says something true about.
    String repoId = "repo-advanced";
    String first = seed(repoId, "steps: []\n");
    advance(repoId);

    assertEquals(CommitHeld.HELD, source.commitHeld(id(repoId), first));
  }

  @Test
  public void anUnreachableHostIsUnknownRatherThanGone() throws Exception {
    // The distinction the caller's decision rests on: a host that could not be asked has said
    // nothing about the commit, and reading that as GONE would discard a run over a blip.
    String repoId = "repo-unreachable";
    String sha = seed(repoId, "steps: []\n");
    host.stop();

    assertEquals(CommitHeld.UNKNOWN, source.commitHeld(id(repoId), sha));
  }

  @Test
  public void aReadCarriesTheBearerAndAMissingOneCostsTheHeaderRatherThanTheCall() throws Exception {
    // Both halves of the credential contract, because the suite runs with the oidc client off and
    // would otherwise say nothing about either. With a token the header is on the wire; without
    // one the request still goes out bare and the git host is left to refuse it — which is what it
    // does, and a 401 read is reportable where an exception on the run worker was not.
    String repoId = "repo-authenticated";
    String sha = seed(repoId, "steps: []\n");

    assertEquals(CommitHeld.HELD, source.commitHeld(id(repoId), sha));
    assertEquals("Bearer machine-token", host.lastAuthorization());

    source.gitHostBearer = java.util.Optional::empty;
    assertEquals(CommitHeld.HELD, source.commitHeld(id(repoId), sha));
    assertNull(host.lastAuthorization(), "a bearerless read must send no header at all");
  }

  @Test
  public void hostileIdentifiersAreRejectedBeforeTheyReachAUrl() {
    assertThrows(BadRequestException.class, () -> source.commitHeld(id("../../etc"), "cafebabe0000"));
    assertThrows(BadRequestException.class, () -> source.commitHeld(id("repo-1"), "not-a-sha"));
    assertThrows(BadRequestException.class, () -> source.readEventTriggers(id("a/../b"), BRANCH));
    assertThrows(
        BadRequestException.class, () -> source.readEventTriggers(id("repo-1"), "../../etc"));
    // The name half, checked only when it is there — the pair reaches the same url.
    assertThrows(
        BadRequestException.class,
        () -> source.commitHeld(CiRepoRef.of("repo-1", "../etc", "repo-1"), "cafebabe0000"));
    assertThrows(
        BadRequestException.class,
        () -> source.commitHeld(CiRepoRef.of("repo-1", "qits", "a/../b"), "cafebabe0000"));
  }

  @Test
  public void aSlashyBranchIsPercentEncodedBecauseARevIsOneSegment() {
    assertTrue(
        source.treeUrl(id("repo-1"), "feature/x", "").endsWith("/tree/feature%2Fx"),
        "a rev is one path segment, so the slash cannot travel raw");
    assertEquals(
        host.gitHostUrl() + "/git/repo-1/blob/feature%2Fx/.config/qits/ci-post-receive.yml",
        source.blobUrl(id("repo-1"), "feature/x", ".config/qits/ci-post-receive.yml"));
  }

  @Test
  public void aNamedRepositoryIsAddressedByProjectAndName() {
    // The public address after the identity cutover. Both routes take the pair, and the storage id
    // does not appear in either.
    CiRepoRef named = CiRepoRef.of("2f1c9b3e-uuid", "qits", "qits-blobstore");
    assertEquals(
        host.gitHostUrl() + "/git/qits/qits-blobstore/tree/main",
        source.treeUrl(named, "main", ""));
    assertEquals(
        host.gitHostUrl()
            + "/git/qits/qits-blobstore/blob/main/.config/qits/ci-post-receive.yml",
        source.blobUrl(named, "main", ".config/qits/ci-post-receive.yml"));
  }

  @Test
  public void aRepositoryWithNoNameKeepsTheIdAddressedUrl() {
    // The compatibility arm: an id-addressed push announces no pair, and such a run reads exactly
    // where this service always read.
    assertEquals(
        host.gitHostUrl() + "/git/repo-1/tree/main", source.treeUrl(id("repo-1"), "main", ""));
    assertEquals(
        host.gitHostUrl() + "/git/repo-1/blob/main/.config/qits/ci-post-receive.yml",
        source.blobUrl(id("repo-1"), "main", ".config/qits/ci-post-receive.yml"));
  }

  @Test
  public void aNamedRepositoryIsReadOverTheNameAddressedRoute() throws Exception {
    // End to end over a real socket: the bare is stored under an opaque id, the host resolves the
    // public pair to it, and neither read mentions the id.
    String storageId = "0f9c2a1b4d6e8f0a1b2c3d4e5f607182";
    String sha = seed(storageId, "steps: []\n", Map.of(".config/qits/ci-event-a.yml", "event: A\n"));
    StubGitHost.alias("qits", "qits-blobstore", storageId);
    CiRepoRef named = CiRepoRef.of(storageId, "qits", "qits-blobstore");

    assertEquals(CommitHeld.HELD, source.commitHeld(named, sha));

    EventTriggerLookup triggers = source.readEventTriggers(named, BRANCH);
    assertEquals(EventTriggerLookup.Status.FOUND, triggers.status());
    assertEquals(sha, triggers.headSha());
    assertEquals(
        List.of(".config/qits/ci-event-a.yml"),
        triggers.files().stream().map(EventTriggerFile::path).toList());
  }

  @Test
  public void aRepositoryOnASlashyBranchIsRead() throws Exception {
    String repoId = "repo-slashy";
    seed(repoId, "steps: []\n", Map.of(".config/qits/ci-event-a.yml", "event: A\n"));
    String sha = branchOff(repoId, "feature/x");

    assertEquals(CommitHeld.HELD, source.commitHeld(id(repoId), sha));
    EventTriggerLookup lookup = source.readEventTriggers(id(repoId), "feature/x");
    assertEquals(EventTriggerLookup.Status.FOUND, lookup.status());
    assertEquals(sha, lookup.headSha());
  }

  // --- the event half: list the config directory at the branch, read each file at that head ---

  @Test
  public void everyTriggerFileIsListedAtTheHeadAndReadAtTheShaTheListingResolved() throws Exception {
    String repoId = "repo-with-triggers";
    String sha =
        seed(
            repoId,
            "steps: []\n",
            Map.of(
                ".config/qits/ci-event-one.yml", "event: A\n",
                ".config/qits/ci-event-two.yml", "event: B\n"));

    EventTriggerLookup lookup = source.readEventTriggers(id(repoId), BRANCH);
    assertEquals(EventTriggerLookup.Status.FOUND, lookup.status());
    assertEquals(sha, lookup.headSha(), "the run records the head the trigger was read at");
    assertEquals(
        Map.of(
            ".config/qits/ci-event-one.yml", "event: A\n",
            ".config/qits/ci-event-two.yml", "event: B\n"),
        lookup.files().stream()
            .collect(Collectors.toMap(EventTriggerFile::path, EventTriggerFile::content)));
  }

  @Test
  public void theFilesAreTheOnesAtTheResolvedHeadEvenWhenTheBranchMovesUnderTheRead()
      throws Exception {
    // The sha-consistency rule, staged: the listing resolves a head, the branch then moves, and the
    // file contents must still be the ones that head carried — never a mix of two commits.
    String repoId = "repo-moving";
    String head = seed(repoId, "steps: []\n", Map.of(".config/qits/ci-event-a.yml", "event: A\n"));
    HttpGitConfigSource pinned =
        new HttpGitConfigSource() {
          @Override
          String blobUrl(CiRepoRef repo, String rev, String path) {
            // Between the listing and the reads, a push lands. The reads are addressed by the sha
            // the listing answered, so what they return is unaffected.
            try {
              rewriteTrigger(repoId);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            return super.blobUrl(repo, rev, path);
          }
    };
    pinned.gitHostUrl = host.gitHostUrl();
    pinned.objectMapper = new ObjectMapper();
    pinned.gitHostBearer = () -> java.util.Optional.of("machine-token");

    EventTriggerLookup lookup = pinned.readEventTriggers(id(repoId), BRANCH);
    assertEquals(head, lookup.headSha());
    assertEquals(
        List.of("event: A\n"), lookup.files().stream().map(EventTriggerFile::content).toList());
    assertNotEquals(head, tip(repoId), "the branch really did move during the read");
  }

  @Test
  public void aRepositoryWithNoConfigDirectoryListsNothingRatherThanFailing() throws Exception {
    String repoId = "repo-bare";
    String sha = seed(repoId, null);
    EventTriggerLookup lookup = source.readEventTriggers(id(repoId), BRANCH);
    assertEquals(EventTriggerLookup.Status.FOUND, lookup.status());
    assertEquals(sha, lookup.headSha());
    assertEquals(List.of(), lookup.files());
  }

  @Test
  public void thePostReceiveConfigAndUnrelatedFilesAreNotTriggerFiles() throws Exception {
    String repoId = "repo-mixed";
    seed(
        repoId,
        "steps: []\n",
        Map.of(
            ".config/qits/ci-event-real.yml", "event: A\n",
            ".config/qits/notes.md", "hello\n",
            ".config/qits/ci-event-nope.yaml", "event: B\n"));

    assertEquals(
        List.of(".config/qits/ci-event-real.yml"),
        source.readEventTriggers(id(repoId), BRANCH).files().stream()
            .map(EventTriggerFile::path)
            .toList());
  }

  @Test
  public void eachScopeReadsItsOwnPrefixAndNeverTheOthers() throws Exception {
    // One directory, two kinds of file. A repository read must not pick up the platform's pipelines
    // and a platform read must not pick up the repository's, or one listing would mean two things.
    String repoId = "repo-scoped";
    seed(
        repoId,
        "steps: []\n",
        Map.of(
            ".config/qits/ci-event-local.yml", "event: A\n",
            ".config/qits/ci-platform-event-bump.yml", "event: B\n"));

    assertEquals(
        List.of(".config/qits/ci-event-local.yml"),
        source.readEventTriggers(id(repoId), BRANCH, CiTriggerScope.REPOSITORY).files().stream()
            .map(EventTriggerFile::path)
            .toList());
    assertEquals(
        List.of(".config/qits/ci-platform-event-bump.yml"),
        source.readEventTriggers(id(repoId), BRANCH, CiTriggerScope.PLATFORM).files().stream()
            .map(EventTriggerFile::path)
            .toList());
  }

  @Test
  public void anUnknownRepositoryOrBranchIsUnreachableRatherThanEmpty() throws Exception {
    // Told apart from "declares no trigger", because the two mean different things to the engine:
    // one is a repository with nothing to say, the other is a repository ci could not ask.
    String repoId = "repo-other-branch";
    seed(repoId, "steps: []\n");
    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        source.readEventTriggers(id(repoId), "no-such-branch").status());
    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        source.readEventTriggers(id("no-such-repo"), BRANCH).status());
  }

  @Test
  public void anUnreachableHostListsNoTriggersAndSaysSo() throws Exception {
    String repoId = "repo-listing-unreachable";
    seed(repoId, "steps: []\n", Map.of(".config/qits/ci-event-a.yml", "event: A\n"));
    host.stop();
    assertEquals(
        EventTriggerLookup.Status.UNREACHABLE,
        source.readEventTriggers(id(repoId), BRANCH).status());
  }

  // --- seeding: real bares under <root>/git/<repoId>, exactly as the git host holds them ---

  private String seed(String repoId, String config) throws Exception {
    return seed(repoId, config, Map.of());
  }

  private String seed(String repoId, String config, Map<String, String> extraFiles)
      throws Exception {
    Path work = Files.createTempDirectory("ci-config-seed");
    try {
      git(null, "init", "-q", "-b", BRANCH, work.toString());
      Files.writeString(work.resolve("readme.txt"), "hello\n");
      if (config != null) {
        write(work, ".config/qits/ci-post-receive.yml", config);
      }
      for (Map.Entry<String, String> extra : extraFiles.entrySet()) {
        write(work, extra.getKey(), extra.getValue());
      }
      commit(work, "seed");
      String sha = git(work, "rev-parse", "HEAD").strip();
      git(null, "clone", "-q", "--bare", work.toString(), bare(repoId).toString());
      return sha;
    } finally {
      StubGitHost.deleteRecursively(work);
    }
  }

  /** A second push onto {@code main}. */
  private void advance(String repoId) throws Exception {
    inAClone(
        repoId,
        BRANCH,
        clone -> {
          write(clone, "later.txt", "later\n");
          commit(clone, "later");
          git(clone, "push", "-q", "origin", BRANCH);
        });
  }

  /** Rewrites the trigger file on {@code main} — a push landing mid-evaluation. */
  private void rewriteTrigger(String repoId) throws Exception {
    inAClone(
        repoId,
        BRANCH,
        clone -> {
          write(clone, ".config/qits/ci-event-a.yml", "event: REWRITTEN\n");
          commit(clone, "rewrite the trigger");
          git(clone, "push", "-q", "origin", BRANCH);
        });
  }

  /** Pushes a new branch with a slash in its name; returns its tip. */
  private String branchOff(String repoId, String branch) throws Exception {
    Path clone = Files.createTempDirectory("ci-config-branch");
    try {
      Files.delete(clone);
      git(null, "clone", "-q", bare(repoId).toString(), clone.toString());
      git(clone, "checkout", "-q", "-b", branch);
      write(clone, "on-the-branch.txt", "yes\n");
      commit(clone, "branch");
      git(clone, "push", "-q", "origin", branch);
      return git(clone, "rev-parse", "HEAD").strip();
    } finally {
      StubGitHost.deleteRecursively(clone);
    }
  }

  private String tip(String repoId) throws Exception {
    return git(bare(repoId), "rev-parse", BRANCH).strip();
  }

  private interface InAClone {
    void run(Path clone) throws Exception;
  }

  private void inAClone(String repoId, String branch, InAClone action) throws Exception {
    Path clone = Files.createTempDirectory("ci-config-clone");
    try {
      Files.delete(clone);
      git(null, "clone", "-q", "-b", branch, bare(repoId).toString(), clone.toString());
      action.run(clone);
    } finally {
      StubGitHost.deleteRecursively(clone);
    }
  }

  private Path bare(String repoId) {
    return root.resolve("git").resolve(repoId);
  }

  private static void write(Path root, String path, String content) throws Exception {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static void commit(Path clone, String message) throws Exception {
    git(clone, "add", ".");
    git(clone, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", message);
  }

  private static String git(Path cwd, String... args) throws Exception {
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
}
