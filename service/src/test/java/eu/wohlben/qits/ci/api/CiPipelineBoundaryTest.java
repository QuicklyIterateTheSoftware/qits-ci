package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole MVP loop at the seams this repo owns (docs/epics/qits-ci/): a post-receive event
 * reaches the intake, ci fetches the pushed commit back from the git host, reads {@code
 * .config/qits/ci-post-receive.yml} out of it, parses the steps and drives them through the step
 * seam — asserted through the public read surface. Docker-free: only the step seam is faked (by
 * {@code eu.wohlben.qits.ci.control.FakeCiStepRunner} in this module's test sources).
 *
 * <p><b>What a step "did" is scripted, not performed.</b> The fake used to clone and run the script
 * as host processes, so assertions here could read a committed file back out of a step's output;
 * that fake died with the approach it modelled, because qits-ci never executes a repository's code.
 * What survives at this level is everything between the intake and the read surface — which config
 * a push produced, how many steps it declared, what each step was asked to run, and how outcomes
 * become rows. What a real container does with a real script is {@code CiDaemonGateIT}'s job.
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

  /** The two statuses a run can hold before it is finished. */
  private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING");

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

  @Inject FakeCiStepRunner fakeRunner;

  @BeforeEach
  void resetRunner() {
    fakeRunner.reset();
  }

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
    // Every run is pinned to one daemon build, resolved before the first container.
    assertEquals("fake-daemon", run.get("daemonVersion"));

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
    assertTrue(steps.get(0).get("output").toString().contains("step 0 ran"));
    assertEquals("SUCCESS", steps.get(1).get("status"));
    assertNotNull(steps.get(1).get("startedAt"), "a step that ran carries host-stamped timestamps");
    assertNotNull(steps.get(1).get("finishedAt"));
    // The run finished, so there is nothing live left to follow.
    assertNull(detail.get("live"), "a finished run must expose no live step");

    // The scripts the config declared reached the seam verbatim, in declaration order — this is the
    // assertion the honest fake used to make by executing them.
    assertEquals(2, fakeRunner.executed().size());
    assertTrue(
        fakeRunner.executed().get(0).script().contains("one-says-$(cat hello.txt)"),
        fakeRunner.executed().get(0).script());
    assertTrue(fakeRunner.executed().get(1).script().contains("two-ran"));
    assertEquals(sha, fakeRunner.executed().get(0).sha());
    assertEquals("ci-green", fakeRunner.executed().get(0).branch());
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
    fakeRunner.script(
        0, new StepResult(7, false, StepOutcome.OK, "before-the-crash"), "before-the-crash");
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
    // The remainder is written when the run closes, terminal like every other row — never PENDING.
    assertNull(steps.get(1).get("startedAt"));
  }

  @Test
  public void cancellingAFinishedRunIsRefusedAndAnUnknownRunIsNotFound() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-done", CONFIG_GREEN);
    postReceive(repoId, "ci-done", ZERO_SHA, sha);
    Map<String, Object> run = awaitTerminalRun(repoId);

    // 409, not a cheerful 202: a finished run has nothing to stop.
    given().when().post("/ci/api/runs/" + run.get("id") + "/cancel").then().statusCode(409);
    given().when().post("/ci/api/runs/no-such-run/cancel").then().statusCode(404);
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

  @Test
  public void theLimitTakesTheNewestNAndItsBoundariesAreTotal() throws Exception {
    String repoId = seedOrigin();
    // Three runs on one repository, pushed in order, so "newest" is a fact rather than a tie.
    String first = pushBranchWithConfig(repoId, "ci-limit-1", CONFIG_GREEN);
    postReceive(repoId, "ci-limit-1", ZERO_SHA, first);
    awaitRunCount(repoId, 1);
    String second = pushBranchWithConfig(repoId, "ci-limit-2", CONFIG_GREEN);
    postReceive(repoId, "ci-limit-2", ZERO_SHA, second);
    awaitRunCount(repoId, 2);
    String third = pushBranchWithConfig(repoId, "ci-limit-3", CONFIG_GREEN);
    postReceive(repoId, "ci-limit-3", ZERO_SHA, third);
    List<Map<String, Object>> all = awaitRunCount(repoId, 3);

    // A limit smaller than the row count takes the head of the same ordering, not a sample.
    List<Map<String, Object>> newest = listRuns(repoId, "1");
    assertEquals(1, newest.size());
    assertEquals(all.get(0).get("id"), newest.get(0).get("id"), "limit=1 must be the newest run");

    // The two boundaries the parameter can get wrong in opposite directions.
    assertEquals(3, listRuns(repoId, "3").size(), "limit exactly the row count returns them all");
    assertEquals(3, listRuns(repoId, "50").size(), "limit above the row count is not an error");
    // Absent still means unbounded, which is what keeps every existing caller unchanged.
    assertEquals(3, listRuns(repoId).size());
    assertEquals(3, listRuns(repoId, "").size(), "an empty value reads as absent");

    // Order survives the bound.
    assertEquals(
        all.stream().map(r -> r.get("id")).toList(),
        listRuns(repoId, "3").stream().map(r -> r.get("id")).toList());
  }

  @Test
  public void aNonPositiveOrNonNumericLimitIsRejectedWithTheMessageEnvelope() throws Exception {
    String repoId = seedOrigin();

    // Zero rows is a question nobody asks and a negative bound is a caller bug — both are 400s
    // rather than empty lists. "abc" is the one that would be a 404 if the parameter were bound to
    // an Integer: JAX-RS answers a query-param conversion failure with NotFoundException.
    for (String bad : List.of("0", "-1", "abc", "1.5", "9999999999999999999")) {
      given()
          .when()
          .get("/ci/api/runs?repositoryId=" + repoId + "&limit=" + bad)
          .then()
          .statusCode(400)
          .body("message", org.hamcrest.Matchers.equalTo("Invalid limit"));
    }

    // The repository filter is still checked first and keeps its own message.
    given()
        .when()
        .get("/ci/api/runs?repositoryId=&limit=5")
        .then()
        .statusCode(400)
        .body("message", org.hamcrest.Matchers.equalTo("Invalid repository id"));
  }

  @Test
  public void theRepositoryListingIsTheDistinctRecordedIdsAscending() throws Exception {
    String busy = seedOrigin();
    String quiet = seedOrigin();
    String neverPushed = seedOrigin();

    postReceive(busy, "ci-repos-a", ZERO_SHA, pushBranchWithConfig(busy, "ci-repos-a", CONFIG_GREEN));
    awaitRunCount(busy, 1);
    postReceive(busy, "ci-repos-b", ZERO_SHA, pushBranchWithConfig(busy, "ci-repos-b", CONFIG_GREEN));
    awaitRunCount(busy, 2);
    postReceive(
        quiet, "ci-repos-c", ZERO_SHA, pushBranchWithConfig(quiet, "ci-repos-c", CONFIG_GREEN));
    awaitRunCount(quiet, 1);

    List<String> ids =
        given()
            .when()
            .get("/ci/api/repositories")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .jsonPath()
            .getList("repositoryIds");

    // Distinct: two runs on one repository is one entry, not two.
    assertEquals(1, ids.stream().filter(busy::equals).count(), "ids must be distinct");
    assertTrue(ids.contains(quiet));
    // Observed, not known: a bare origin ci has never recorded a run against has no history to
    // explore, and this listing must not promise one. (CiCandidateRepos is the wider question.)
    assertFalse(ids.contains(neverPushed), "a repository with no run must not be listed");
    // Ascending, so a client can diff it against another service's list without re-sorting. The
    // suite shares one instance, so the assertion is about the whole answer rather than these ids.
    assertEquals(ids.stream().sorted().toList(), ids, "the listing must be sorted ascending");
  }

  @Test
  public void theActiveRouteIsTheListingAndNotASingleRunNamedActive() {
    // /runs/active and /runs/{runId} share a prefix. JAX-RS ranks a literal segment above a
    // template, so this resolves to the listing — but "surely the spec sorts it right" is exactly
    // the kind of belief that surfaces as a silently 404ing client, so it is asserted rather than
    // reasoned about. If the template ever captured it, requireRun("active") would 404 here.
    given()
        .when()
        .get("/ci/api/runs/active")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        // The listing envelope, not a run object: `runs` is present and the single-run shape is not.
        .body("$", org.hamcrest.Matchers.hasKey("runs"))
        .body("id", org.hamcrest.Matchers.nullValue())
        .body("commitSha", org.hamcrest.Matchers.nullValue());
  }

  @Test
  public void theActiveListingHoldsTheQueuedAndRunningRunsAndDropsThemWhenTheyFinish()
      throws Exception {
    // Staged against a genuinely occupied worker rather than a sleep: the run worker is
    // single-threaded, so parking one run inside its first step is what makes the next one queue.
    // Both states are then real at one instant and the endpoint is asked about that instant.
    String busy = seedOrigin();
    String waiting = seedOrigin();
    String busySha = pushBranchWithConfig(busy, "ci-active-1", CONFIG_GREEN);
    String waitingSha = pushBranchWithConfig(waiting, "ci-active-2", CONFIG_GREEN);

    CompletableFuture<String> inStepZero = new CompletableFuture<>();
    CountDownLatch release = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          inStepZero.complete(spec.runId());
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    postReceive(busy, "ci-active-1", ZERO_SHA, busySha);
    String runningId = inStepZero.get(30, TimeUnit.SECONDS);
    // The intake writes the row before it answers, so this run is on the record the moment the 202
    // lands — no polling needed for it to be findable.
    postReceive(waiting, "ci-active-2", ZERO_SHA, waitingSha);

    try {
      List<Map<String, Object>> active = listActiveRuns();
      List<String> ids = active.stream().map(run -> (String) run.get("id")).toList();
      Map<String, Object> queued = runIn(active, waiting);
      assertEquals("QUEUED", queued.get("status"), "the accepted run is on the record, not started");
      assertEquals("RUNNING", runIn(active, busy).get("status"));
      // Newest first across repositories, with no parameter asked for either.
      assertTrue(
          ids.indexOf((String) queued.get("id")) < ids.indexOf(runningId),
          "the newer run must come first");
      // Everything the suite finished earlier is terminal, so nothing terminal may be here.
      assertTrue(
          active.stream()
              .noneMatch(run -> List.of("SUCCESS", "FAILED", "CONFIG_ERROR").contains(run.get("status"))),
          "a finished run has no business in the active listing");
      // The list shape excludes step output, exactly as the run listing does.
      assertNull(queued.get("steps"), "the active listing must not carry step output");
      assertNull(queued.get("live"));
    } finally {
      release.countDown();
    }

    awaitTerminalRun(busy);
    awaitTerminalRun(waiting);
    // Both are terminal now, so neither is here. That an empty answer means "CI is idle" rather than
    // "nothing was accepted" is only sayable because a queued run is a row.
    List<Map<String, Object>> afterwards = listActiveRuns();
    assertTrue(
        afterwards.stream()
            .noneMatch(
                run -> busy.equals(run.get("repoId")) || waiting.equals(run.get("repoId"))),
        "a terminal run leaves the active listing");
  }

  private static Map<String, Object> runIn(List<Map<String, Object>> runs, String repoId) {
    return runs.stream()
        .filter(run -> repoId.equals(run.get("repoId")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no active run for " + repoId));
  }

  @Test
  public void theRepositorySummaryCarriesTheNewestRunAndTheNewestMainRun() throws Exception {
    String bothBranches = seedOrigin();
    String featureOnly = seedOrigin();

    // main first, then a newer run on another branch — so lastRun and lastMainRun differ and the
    // endpoint has to answer two different questions about one repository.
    postReceive(
        bothBranches, "main", ZERO_SHA, pushBranchWithConfig(bothBranches, "main", CONFIG_GREEN));
    awaitRunCount(bothBranches, 1);
    postReceive(
        bothBranches,
        "ci-summary-feature",
        ZERO_SHA,
        pushBranchWithConfig(bothBranches, "ci-summary-feature", CONFIG_GREEN));
    List<Map<String, Object>> bothRuns = awaitRunCount(bothBranches, 2);

    postReceive(
        featureOnly,
        "ci-summary-only",
        ZERO_SHA,
        pushBranchWithConfig(featureOnly, "ci-summary-only", CONFIG_GREEN));
    awaitRunCount(featureOnly, 1);

    List<Map<String, Object>> summaries =
        given()
            .when()
            .get("/ci/api/repositories/summary")
            .then()
            .statusCode(200)
            .contentType(ContentType.JSON)
            .extract()
            .jsonPath()
            .getList("repositories");

    Map<String, Object> both = summaryFor(summaries, bothBranches);
    Map<String, Object> lastRun = asMap(both.get("lastRun"));
    Map<String, Object> lastMainRun = asMap(both.get("lastMainRun"));
    assertEquals(bothRuns.get(0).get("id"), lastRun.get("id"), "lastRun is the newest, any branch");
    assertEquals("ci-summary-feature", lastRun.get("branch"));
    assertEquals("main", lastMainRun.get("branch"), "lastMainRun is the newest run on main");
    assertEquals(bothRuns.get(1).get("id"), lastMainRun.get("id"));
    // Full run DTOs in both slots, minus what no listing carries.
    assertEquals("SUCCESS", lastRun.get("status"));
    assertEquals("POST_RECEIVE", lastRun.get("triggerType"));
    assertNotNull(lastRun.get("commitSha"));
    assertNull(lastRun.get("steps"), "a summary carries no step output");
    assertNull(lastRun.get("live"));

    // A repository that has never run on main says so with a null rather than by omitting the key
    // or by falling back to its newest run.
    Map<String, Object> feature = summaryFor(summaries, featureOnly);
    assertEquals("ci-summary-only", asMap(feature.get("lastRun")).get("branch"));
    assertNull(feature.get("lastMainRun"), "no run on main is a null lastMainRun");

    // Ascending by repositoryId, the same ordering GET /ci/api/repositories answers with and for the
    // same reason: a client diffing the two must not have to re-sort either.
    List<String> ids = summaries.stream().map(s -> (String) s.get("repositoryId")).toList();
    assertEquals(ids.stream().sorted().toList(), ids, "summaries must be sorted ascending");
    // And it is the same set of repositories, not a different one.
    assertEquals(
        given().when().get("/ci/api/repositories").then().extract().jsonPath()
            .getList("repositoryIds"),
        ids,
        "the summary must cover exactly the repositories the id listing names");
  }

  private static Map<String, Object> summaryFor(
      List<Map<String, Object>> summaries, String repositoryId) {
    return summaries.stream()
        .filter(summary -> repositoryId.equals(summary.get("repositoryId")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no summary for " + repositoryId));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    assertNotNull(value, "expected a run object");
    return (Map<String, Object>) value;
  }

  private List<Map<String, Object>> listActiveRuns() {
    return given()
        .when()
        .get("/ci/api/runs/active")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .extract()
        .jsonPath()
        .getList("runs");
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

  /**
   * Clones, commits the config on a branch, pushes it; returns the pushed sha. {@code main} is the
   * branch every seeded origin is already on, so it is committed to rather than created.
   */
  private String pushBranchWithConfig(String repoId, String branch, String config)
      throws Exception {
    Path clone = cloneRepo(repoId);
    if (!"main".equals(branch)) {
      git(clone, "checkout", "-q", "-b", branch);
    }
    Path configFile = clone.resolve(".config/qits/ci-post-receive.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, config);
    // A second push of the same config to the same repository would otherwise be an empty commit,
    // which git refuses — and a repository needs several pushes to have a run history worth reading.
    Files.writeString(clone.resolve("branch.txt"), branch + " " + UUID.randomUUID() + "\n");
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
    return listRuns(repoId, null);
  }

  /** {@code limit} null omits the parameter entirely; {@code ""} sends it with no value. */
  private List<Map<String, Object>> listRuns(String repoId, String limit) {
    return given()
        .when()
        .get("/ci/api/runs?repositoryId=" + repoId + (limit == null ? "" : "&limit=" + limit))
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("runs");
  }

  /** Deadline-polls the run list until it holds exactly {@code expected} rows; returns them. */
  private List<Map<String, Object>> awaitRunCount(String repoId, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = listRuns(repoId);
      if (runs.size() == expected) {
        return runs;
      }
      Thread.sleep(100);
    }
    return fail("no " + expected + " CI runs for " + repoId + " within the deadline");
  }

  /**
   * Deadline-polls the run list until the (single) run reaches a terminal status.
   *
   * <p>Both non-terminal states are named. {@code QUEUED} is a row now, so a run that has been
   * accepted and not yet started reaches this listing — treating it as terminal would let every
   * caller here assert against a run that has not run.
   */
  private Map<String, Object> awaitTerminalRun(String repoId) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = listRuns(repoId);
      if (runs.size() == 1 && !ACTIVE.contains(runs.get(0).get("status"))) {
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
