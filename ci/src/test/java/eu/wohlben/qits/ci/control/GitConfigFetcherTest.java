package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

  /**
   * Creates a bare repo at {@code <base>/git/<repoId>} whose tip commit carries the config content
   * (or no config file at all for {@code null}); returns the tip sha.
   */
  private String seedServedRepo(String repoId, String configContent) throws Exception {
    Path work = Files.createTempDirectory("ci-fetch-work");
    try {
      git(null, "init", "-q", "-b", "main", work.toString());
      Files.writeString(work.resolve("readme.txt"), "hello\n");
      if (configContent != null) {
        Path config = work.resolve(CiConfigParser.CONFIG_PATH);
        Files.createDirectories(config.getParent());
        Files.writeString(config, configContent);
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
