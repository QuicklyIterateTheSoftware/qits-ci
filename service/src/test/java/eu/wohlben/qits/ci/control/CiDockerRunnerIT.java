package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.control.CiStepRunner.StepSpec;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Extended (real docker) coverage of {@link CiDockerRunner} — the one seam the docker-free suites
 * fake: a real {@code docker run} of a step whose composite clones the repo at the pushed sha from
 * an HTTP-served bare (the {@code DaemonSelfCloneIT} dumb-http mechanics) and executes the script
 * in {@code /workspace}. Plain JUnit, deliberately not {@code @QuarkusTest} (the {@code
 * FakeCiStepRunner} {@code @Mock} would shadow the real runner). Self-skips when docker or the
 * image is absent. Run with {@code -Pextended}.
 */
@Tag("extended")
public class CiDockerRunnerIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");
  private static final String REPO_ID = "ci-runner-it-repo";

  @Test
  public void greenStepClonesAtTheShaAndCapturesOutput() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    withServedRepo(
        (runner, sha) -> {
          StepResult result =
              runner.run(
                  new StepSpec(
                      UUID.randomUUID().toString(),
                      0,
                      REPO_ID,
                      sha,
                      IMAGE,
                      "echo marker-$(cat hello.txt) && pwd"));
          assertEquals(0, result.exitCode(), result.output());
          assertTrue(result.workspaceReady(), "the prelude must report a ready workspace");
          // The script read the committed file from the fresh clone, with CWD /workspace.
          assertTrue(result.output().contains("marker-hello-from-ci-it"), result.output());
          assertTrue(result.output().contains("/workspace"), result.output());
          // The sentinel is internal plumbing — it must not leak into a user-visible log.
          assertFalse(
              result.output().contains(CiDockerRunner.PRELUDE_FAILED_MARKER),
              "sentinel must be stripped from step output");
        });
  }

  @Test
  public void failingScriptYieldsItsExitCodeWithPriorOutput() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    withServedRepo(
        (runner, sha) -> {
          StepResult result =
              runner.run(
                  new StepSpec(
                      UUID.randomUUID().toString(),
                      0,
                      REPO_ID,
                      sha,
                      IMAGE,
                      "echo before-the-crash\nexit 7"));
          assertEquals(7, result.exitCode(), result.output());
          assertTrue(result.workspaceReady(), "a failing script still had a ready workspace");
          assertTrue(result.output().contains("before-the-crash"), result.output());
        });
  }

  @Test
  public void unreachableCommitReportsAnUnreadyWorkspaceRatherThanAScriptFailure()
      throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    withServedRepo(
        (runner, sha) -> {
          // A sha that is not in the repo stands in for a commit force-pushed away mid-queue: the
          // prelude fails, so the script never runs and the run must not be recorded red.
          StepResult result =
              runner.run(
                  new StepSpec(
                      UUID.randomUUID().toString(),
                      0,
                      REPO_ID,
                      "0".repeat(40),
                      IMAGE,
                      "echo should-never-run"));
          assertFalse(result.workspaceReady(), result.output());
          assertFalse(result.output().contains("should-never-run"), result.output());
        });
  }

  private interface StepCase {
    void run(CiDockerRunner runner, String sha) throws Exception;
  }

  /** Serves a one-commit bare over dumb HTTP and hands a wired runner + the tip sha to the case. */
  private void withServedRepo(StepCase testCase) throws Exception {
    Path work = Files.createTempDirectory("ci-runner-it");
    Vertx vertx = Vertx.vertx();
    HttpServer server = vertx.createHttpServer();
    try {
      Path bare = prepareServedBareRepo(work);
      String sha = exec(null, "git", "-C", bare.toString(), "rev-parse", "HEAD").trim();
      server.requestHandler(
          req -> {
            String prefix = "/git/" + REPO_ID + "/";
            Path file = bare.resolve(req.path().substring(prefix.length())).normalize();
            if (!req.path().startsWith(prefix)
                || !file.startsWith(bare)
                || !Files.isRegularFile(file)) {
              req.response().setStatusCode(404).end();
              return;
            }
            try {
              req.response()
                  .putHeader("Content-Type", "application/octet-stream")
                  .end(Buffer.buffer(Files.readAllBytes(file)));
            } catch (Exception e) {
              req.response().setStatusCode(500).end();
            }
          });
      int port =
          server
              .listen(0, "0.0.0.0")
              .toCompletionStage()
              .toCompletableFuture()
              .get(5, TimeUnit.SECONDS)
              .actualPort();

      CiDockerRunner runner = new CiDockerRunner();
      runner.runtime = RUNTIME;
      runner.network = "qits-net";
      runner.containerGitUrl = "http://host.docker.internal:" + port;
      runner.stepTimeoutSeconds = 300;
      runner.outputMaxChars = 65536;
      runner.memoryLimit = "2g";
      runner.pidsLimit = "1024";
      runner.cpus = "2";
      // ensureNetwork() directly, not the StartupEvent observer — this is a plain JUnit IT with no
      // Quarkus, and the observer deliberately no-ops under TEST launch mode.
      runner.ensureNetwork();

      testCase.run(runner, sha);
    } finally {
      server.close();
      vertx.close();
      deleteRecursively(work);
    }
  }

  /** A bare repo with a single commit, {@code update-server-info}'d for dumb HTTP. */
  private static Path prepareServedBareRepo(Path work) throws Exception {
    Path src = work.resolve("src");
    Files.createDirectories(src);
    exec(src, "git", "init", "-q", "-b", "main");
    exec(src, "git", "config", "user.email", "it@qits.local");
    exec(src, "git", "config", "user.name", "qits-it");
    Files.writeString(src.resolve("hello.txt"), "hello-from-ci-it");
    exec(src, "git", "add", "hello.txt");
    exec(src, "git", "commit", "-q", "-m", "initial");
    Path bare = work.resolve("served.git");
    exec(work, "git", "clone", "-q", "--bare", src.toString(), bare.toString());
    exec(bare, "git", "update-server-info");
    return bare;
  }

  private boolean dockerAndImageAvailable() {
    try {
      return new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static String exec(Path cwd, String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException(String.join(" ", argv) + " failed:\n" + out);
    }
    return out;
  }

  private static void deleteRecursively(Path root) throws Exception {
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
