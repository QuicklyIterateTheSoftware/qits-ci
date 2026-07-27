package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The whole MVP loop at the seams this repo owns (docs/epics/qits-ci/): a post-receive event
 * reaches the intake, ci fetches the pushed commit back from the git host, reads {@code
 * .config/qits/ci-post-receive.yml} out of it, and the (host-process) fake runner executes the
 * steps against a real clone at the pushed sha — asserted through the public read surface.
 * Docker-free: only {@code CiDockerRunner} is faked (by {@code
 * eu.wohlben.qits.ci.control.FakeCiStepRunner} in this module's test sources).
 *
 * <p><b>Where the loop starts.</b> The git host is not in this repo — it belongs to qits-artifacts,
 * and it reaches ci over HTTP (its {@code CiPostReceiveNotifier} POSTs to {@code
 * qits.ci.intake-url}). So the test pushes into a real bare origin laid out as {@code
 * <git-host>/git/<repoId>} and addressed over {@code file://}, then POSTs the event itself — byte
 * for byte the payload the notifier sends. That is exactly the surface an extracted ci service
 * sees. The monorepo's version of this test drove a real {@code git push} through the in-process
 * git host and let the hook fire; the assertions about the *hook's own* filtering (a branch
 * deletion must not produce an event) went with the hook and belong to qits-artifacts.
 */
@QuarkusTest
public class CiPipelineBoundaryTest {

  /** The all-zero sha git reports as the old id of a newly created branch. */
  private static final String ZERO_SHA = "0".repeat(40);

  private static final String CONFIG_GREEN =
      """
      steps:
        - image: alpine:3
          script: echo one-says-$(cat hello.txt)
        - image: alpine:3
          script: |
            echo two-ran
      """;

  @ConfigProperty(name = "qits.ci.git-host-url")
  String gitHostUrl;

  @Test
  public void pushWithConfigRecordsAGreenRunWithStepOutputs() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-green", CONFIG_GREEN);
    postReceive(repoId, "ci-green", ZERO_SHA, sha);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals("ci-green", run.get("branch"));
    assertEquals(sha, run.get("commitSha"));
    assertNull(run.get("steps"), "listing must not carry step output");

    JsonPath detail =
        given()
            .when()
            .get("/ci/api/runs/" + run.get("id"))
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
    String sha =
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
    postReceive(repoId, "ci-red", ZERO_SHA, sha);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("FAILED", run.get("status"));
    JsonPath detail =
        given().when().get("/ci/api/runs/" + run.get("id")).then().extract().jsonPath();
    List<Map<String, Object>> steps = detail.getList("steps");
    assertEquals("FAILED", steps.get(0).get("status"));
    assertEquals(7, steps.get(0).get("exitCode"));
    assertTrue(steps.get(0).get("output").toString().contains("before-the-crash"));
    assertEquals("SKIPPED", steps.get(1).get("status"));
  }

  @Test
  public void malformedConfigRecordsAConfigErrorRun() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-broken", "steps: [unclosed\n");
    postReceive(repoId, "ci-broken", ZERO_SHA, sha);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("CONFIG_ERROR", run.get("status"));
    JsonPath detail =
        given().when().get("/ci/api/runs/" + run.get("id")).then().extract().jsonPath();
    assertEquals(0, detail.getList("steps").size());
  }

  @Test
  public void pushWithoutConfigRecordsNoRun() throws Exception {
    String repoId = seedOrigin();
    Path clone = cloneRepo(repoId);
    git(clone, "checkout", "-q", "-b", "ci-silent");
    Files.writeString(clone.resolve("plain.txt"), "no ci here\n");
    commitAll(clone, "plain change");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", "ci-silent");
    postReceive(repoId, "ci-silent", ZERO_SHA, sha);

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
    String replaced = git(clone, "rev-parse", "HEAD").trim();
    Files.writeString(clone.resolve("extra.txt"), "rewritten\n");
    commitAll(clone, "amended");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "--force", "origin", "ci-rewritten");
    postReceive(repoId, "ci-rewritten", replaced, sha);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(sha, run.get("commitSha"), "the recorded run must belong to the pushed tip");
    assertEquals(1, listRuns(repoId).size(), "one ref update ⇒ one run");
  }

  @Test
  public void runListingRequiresARepositoryFilter() {
    // The repository is scope, and it moved from the path into ?repositoryId= — so a caller that
    // omits it must be told, not handed every run on the instance or a misleading empty list.
    given().when().get("/ci/api/runs").then().statusCode(400);
  }

  // --- the wire contract the git host speaks (CiPostReceiveNotifier's payload) ---

  private void postReceive(String repoId, String branch, String oldSha, String newSha) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("repoId", repoId, "branch", branch, "oldSha", oldSha, "newSha", newSha))
        .when()
        .post("/ci/api/events/post-receive")
        .then()
        .statusCode(202);
  }

  // --- git plumbing (the git host stands in as <base>/git/<repoId> over file://) ---

  /** The directory this suite's {@code qits.ci.git-host-url} points at. */
  private Path gitHostRoot() {
    return Path.of(gitHostUrl.replaceFirst("^file://", ""), "git");
  }

  /**
   * Seeds a bare origin at {@code <git-host>/git/<repoId>} holding one commit with {@code
   * hello.txt} — built here rather than cloned from a fixture, so the suite needs no submodule.
   */
  private String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path seed = Files.createTempDirectory("ci-boundary-seed");
    git(seed, "init", "-q", "-b", "main");
    Files.writeString(seed.resolve("hello.txt"), "hello\n");
    commitAll(seed, "initial");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  private Path cloneRepo(String repoId) throws Exception {
    Path clone = Files.createTempDirectory("ci-boundary-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitHostRoot().resolve(repoId).toString(), clone.toString());
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
        .get("/ci/api/runs?repositoryId=" + repoId)
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
