package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The whole MVP loop at its real seams (docs/epics/qits-ci/): a real {@code git push} through the
 * in-process git host fires the post-receive hook, whose HTTP event reaches the intake; ci fetches
 * the pushed commit back from the git host, reads {@code .config/qits/ci-post-receive.yml}, and the
 * (host-process) fake runner executes the steps against a real clone at the pushed sha — asserted
 * through the public read surface. Docker-free: only {@code CiDockerRunner} is faked (by {@code
 * eu.wohlben.qits.ci.control.FakeCiStepRunner} in this module's test sources).
 */
@QuarkusTest
public class CiPipelineBoundaryTest {

  private static final String CONFIG_GREEN =
      """
      steps:
        - image: alpine:3
          script: echo one-says-$(cat hello.txt)
        - image: alpine:3
          script: |
            echo two-ran
      """;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @TestHTTPResource("/git")
  URL gitBase;

  private final String fixtureUrl;

  public CiPipelineBoundaryTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  @Test
  public void pushWithConfigRecordsAGreenRunWithStepOutputs() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-green", CONFIG_GREEN);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals("ci-green", run.get("branch"));
    assertEquals(sha, run.get("commitSha"));
    assertNull(run.get("steps"), "listing must not carry step output");

    JsonPath detail =
        given()
            .when()
            .get("/api/ci/runs/" + run.get("id"))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    List<Map<String, Object>> steps = detail.getList("steps");
    assertEquals(2, steps.size());
    assertEquals("SUCCESS", steps.get(0).get("status"));
    assertEquals(0, steps.get(0).get("exitCode"));
    // The step really ran against a clone of the pushed commit (reads the committed file).
    assertTrue(steps.get(0).get("output").toString().contains("one-says-hello"));
    assertEquals("SUCCESS", steps.get(1).get("status"));
    assertTrue(steps.get(1).get("output").toString().contains("two-ran"));
  }

  @Test
  public void failingScriptRecordsTheExitCodeAndSkipsTheRest() throws Exception {
    String repoId = seedOrigin();
    pushBranchWithConfig(
        repoId,
        "ci-red",
        """
        steps:
          - image: alpine:3
            script: |
              echo before-the-crash
              exit 7
          - image: alpine:3
            script: echo never-runs
        """);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("FAILED", run.get("status"));
    JsonPath detail =
        given().when().get("/api/ci/runs/" + run.get("id")).then().extract().jsonPath();
    List<Map<String, Object>> steps = detail.getList("steps");
    assertEquals("FAILED", steps.get(0).get("status"));
    assertEquals(7, steps.get(0).get("exitCode"));
    assertTrue(steps.get(0).get("output").toString().contains("before-the-crash"));
    assertEquals("SKIPPED", steps.get(1).get("status"));
  }

  @Test
  public void malformedConfigRecordsAConfigErrorRun() throws Exception {
    String repoId = seedOrigin();
    pushBranchWithConfig(repoId, "ci-broken", "steps: [unclosed\n");

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("CONFIG_ERROR", run.get("status"));
    JsonPath detail =
        given().when().get("/api/ci/runs/" + run.get("id")).then().extract().jsonPath();
    assertEquals(0, detail.getList("steps").size());
  }

  @Test
  public void pushWithoutConfigRecordsNoRun() throws Exception {
    String repoId = seedOrigin();
    Path clone = cloneRepo(repoId);
    git(clone, "checkout", "-q", "-b", "ci-silent");
    Files.writeString(clone.resolve("plain.txt"), "no ci here\n");
    commitAll(clone, "plain change");
    git(clone, "push", "-q", "origin", "ci-silent");

    Thread.sleep(1500); // grace for the (absent) async run to have appeared
    assertEquals(0, listRuns(repoId).size(), "a config-less push must record nothing");
  }

  @Test
  public void forcePushRecordsOneRunForTheSurvivingTip() throws Exception {
    // A force-push is one received ref update, so it yields exactly one run — for the tip that
    // exists. (The orphaned-commit case needs the event to arrive before the rewrite lands, a race
    // this level cannot stage; it is covered directly in the ci module by
    // GitConfigFetcherTest.commitForcePushedAwayIsGone and CiRunServiceTest's GONE cases.)
    String repoId = seedOrigin();
    Path clone = cloneRepo(repoId);
    git(clone, "checkout", "-q", "-b", "ci-rewritten");
    Path configFile = clone.resolve(".config/qits/ci-post-receive.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, CONFIG_GREEN);
    commitAll(clone, "add ci config");
    Files.writeString(clone.resolve("extra.txt"), "rewritten\n");
    commitAll(clone, "amended");
    git(clone, "push", "-q", "--force", "origin", "ci-rewritten");

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(
        git(clone, "rev-parse", "HEAD").trim(),
        run.get("commitSha"),
        "the recorded run must belong to the pushed tip");
    assertEquals(1, listRuns(repoId).size(), "one ref update ⇒ one run");
  }

  @Test
  public void branchDeletionRecordsNoRun() throws Exception {
    String repoId = seedOrigin();
    pushBranchWithConfig(repoId, "ci-doomed", CONFIG_GREEN);
    awaitTerminalRun(repoId); // the creation push's run

    Path clone = cloneRepo(repoId);
    git(clone, "push", "-q", "origin", ":ci-doomed");
    Thread.sleep(1500); // grace for a (wrongly) triggered run to have appeared
    assertEquals(1, listRuns(repoId).size(), "a deletion must not trigger a run");
  }

  // --- git plumbing (the GitHostTest mechanics) ---

  /** Seeds a bare origin at {@code <data-dir>/<repoId>/origin} from the fixture. */
  private String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path origin = Path.of(dataDir, repoId, "origin");
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", fixtureUrl, origin.toString());
    return repoId;
  }

  private Path cloneRepo(String repoId) throws Exception {
    Path clone = Files.createTempDirectory("ci-boundary-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitBase + "/" + repoId, clone.toString());
    return clone;
  }

  /** Clones, commits the config on a new branch, pushes it; returns the pushed sha. */
  private String pushBranchWithConfig(String repoId, String branch, String config)
      throws Exception {
    Path clone = cloneRepo(repoId);
    git(clone, "checkout", "-q", "-b", branch);
    Path configFile = clone.resolve(".config/qits/ci-post-receive.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, config);
    commitAll(clone, "add ci config");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", branch);
    return sha;
  }

  private void commitAll(Path clone, String message) throws Exception {
    git(clone, "add", ".");
    git(clone, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", message);
  }

  private List<Map<String, Object>> listRuns(String repoId) {
    return given()
        .when()
        .get("/api/ci/repositories/" + repoId + "/runs")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("runs");
  }

  /** Deadline-polls the run list until the (single) run reaches a terminal status. */
  private Map<String, Object> awaitTerminalRun(String repoId) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = listRuns(repoId);
      if (runs.size() == 1 && !"RUNNING".equals(runs.get(0).get("status"))) {
        return runs.get(0);
      }
      Thread.sleep(100);
    }
    return fail("no terminal CI run for " + repoId + " within the deadline");
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
}
