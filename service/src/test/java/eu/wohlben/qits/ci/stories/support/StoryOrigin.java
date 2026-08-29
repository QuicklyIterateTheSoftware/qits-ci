package eu.wohlben.qits.ci.stories.support;

import eu.wohlben.qits.ci.githost.StubGitHost;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The repository a story's build is about, provisioned the way nobody in the story provisions it.
 *
 * <h2>Setup is invisible to both taps, by construction</h2>
 *
 * <p>A story's diagram must show the walk somebody takes, not the fixture somebody built. So this
 * class touches neither of the two things a story is observed through: it makes no RestAssured call
 * (the framework's tap would draw an arrow into qits-ci) and it sends nothing over HTTP to the stub
 * git host (whose request log is the other tap). It writes a bare repository <b>onto the stub's
 * disk</b> with a plain {@link ProcessBuilder} {@code git}, which is a plane neither tap can see —
 * and which is also the truth about a platform whose repositories are seeded straight onto the git
 * host.
 *
 * <p>The commands are deliberately <em>not</em> run through {@code Commands} either. That facade
 * records a step per command, and a story about a build being triggered whose first six steps are
 * {@code git init} / {@code git add} / {@code git commit} is a story about git.
 *
 * <h2>The ids are fixed and readable</h2>
 *
 * <p>One repository per story, named for the story, and <b>deleted then recreated</b> before every
 * run rather than randomised. A generated UUID would buy nothing here — the delete is what makes
 * the state known — and it would cost every label its meaning, because {@link
 * eu.wohlben.qits.userflows.Labels} rewrites a whole UUID path segment to {@code {id}} and the
 * diagram would say {@code GET /git/{id}/tree/main} for every story alike. {@code
 * story-event-build} says which repository ci read.
 *
 * <p>They are also what makes the stories re-runnable: the stub's root survives a build, so a
 * second run would otherwise find a repository whose {@code main} already carries the trigger file
 * at a different sha.
 */
public final class StoryOrigin {

  /** The branch every story's repository publishes, and the one an event-triggered run reads. */
  public static final String BRANCH = "main";

  /** Where a repository's pipeline and trigger files live — qits-ci reads this directory, not the root. */
  public static final String CONFIG_DIR = ".config/qits";

  private StoryOrigin() {}

  /** {@code <stub root>/git/<repoId>} — the bare the stub serves as {@code /git/<repoId>}. */
  public static Path bare(String repoId) {
    return StubGitHost.ROOT.resolve("git").resolve(repoId);
  }

  /**
   * Publish one repository holding one file at {@code main}, and answer the commit sha it left
   * there — which is the commit an event-triggered run builds, since an event names no ref and the
   * tracked branch supplies one.
   *
   * <p>Delete-then-create: see the class javadoc. The seed working tree is a temp directory that
   * nothing reads afterwards; what the stub serves is the bare cloned out of it.
   */
  public static String publish(String repoId, String configFileName, String configYaml)
      throws Exception {
    Path bare = bare(repoId);
    deleteRecursively(bare);
    Files.createDirectories(bare.getParent());

    Path seed = Files.createTempDirectory("ci-story-seed-" + repoId + "-");
    git(seed, "init", "-q", "-b", BRANCH);
    Path configFile = seed.resolve(CONFIG_DIR).resolve(configFileName);
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, configYaml);
    // A second file, so the tree read at the config directory is not the only thing in the commit
    // and a story can say "an ordinary repository" without qualification.
    Files.writeString(seed.resolve("README.md"), "# " + repoId + "\n");
    git(seed, "add", ".");
    git(
        seed,
        "-c",
        "user.email=stories@qits.local",
        "-c",
        "user.name=a maintainer",
        "commit",
        "-q",
        "-m",
        "declare the pipeline");
    String sha = git(seed, "rev-parse", "HEAD").strip();
    git(null, "clone", "-q", "--bare", seed.toString(), bare.toString());
    return sha;
  }

  /**
   * Wait for a freshly published repository to become a <b>candidate</b>.
   *
   * <p>qits-ci asks the git host which repositories exist and caches the answer for five seconds
   * ({@code HttpGitHostRepoListing.CACHE_TTL}), so that a burst of events on the bus costs one
   * listing read rather than one per frame. A repository published inside that window is simply not
   * in the candidate set yet, and an event that should have matched it comes back {@code
   * runIds: []} with nothing skipped — which is the engine correctly saying "I asked everybody and
   * none of them selected this" about a repository it had not heard of. Measured 2026-08-29: a story
   * class publishing its repository and triggering immediately after a neighbouring class's
   * evaluation got exactly that, and the failure names the assertion rather than the cache.
   *
   * <p>It is a fixture waiting for the service's own cache, not a workaround for a race in it: a
   * real repository is created long before anyone releases anything, and five seconds of staleness
   * is what a burst-proof listing costs. The margin is generous because the window starts at the
   * neighbouring read, which this class cannot see.
   */
  public static void awaitCandidateListing() {
    try {
      Thread.sleep(7_000);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Whether this machine can seed anything at all — a story class gates itself on it. */
  public static boolean gitPresent() {
    try {
      return new ProcessBuilder("git", "--version").start().waitFor() == 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (Exception noGit) {
      return false;
    }
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    if (cwd != null) {
      builder.directory(cwd.toFile());
    }
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + output);
    }
    return output;
  }

  private static void deleteRecursively(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
  }
}
