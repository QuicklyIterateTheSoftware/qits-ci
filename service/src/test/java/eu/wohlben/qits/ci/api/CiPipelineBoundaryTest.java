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
import eu.wohlben.qits.ci.bus.ScmPublishCommitListener;
import eu.wohlben.qits.ci.bus.ScmPushFrames;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole MVP loop at the seams this repo owns (docs/epics/qits-ci/): an SCMPublishCommit
 * reaches the push listener, ci fetches the pushed commit back from the git host, reads {@code
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
 * <p><b>Where the loop starts.</b> The git host is not in this repo — it is qits-githost, and it
 * reaches ci through the event log: a push publishes {@code SCMPublishCommit}, and {@code
 * bus/ScmPublishCommitListener} is what qits-ci does with one. So the test pushes into a real bare
 * origin laid out as {@code <git-host>/git/<repoId>} and served by {@code StubGitHost}, then hands
 * the listener a frame built from a real {@code SCMPublishCommit} — byte for byte the payload
 * qits-githost publishes ({@code bus/ScmPushFrames}).
 *
 * <p>This used to POST {@code /ci/api/events/post-receive} instead, and nothing below moved with the
 * transport: the endpoint is gone, the listener accepts the same push the same way, and every case
 * here — green, red, dedupe, supersede, the config that could not be read — is now coverage of that
 * listener's path. The monorepo's version drove a real {@code git push} and let the hook fire; the
 * assertions about the host's own filtering (a branch deletion must not produce a build) belong to
 * qits-githost, which publishes {@code SCMDeleteBranch} that nothing here subscribes to.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
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

  @Inject FakeCiStepRunner fakeRunner;

  @Inject ScmPublishCommitListener pushes;

  @BeforeEach
  void resetRunner() {
    fakeRunner.reset();
  }

  @Test
  public void pushWithConfigRecordsAGreenRunWithStepOutputs() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-green", CONFIG_GREEN);
    announcePush(repoId, "ci-green", ZERO_SHA, sha);

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
    announcePush(repoId, "ci-red", ZERO_SHA, sha);

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
    announcePush(repoId, "ci-done", ZERO_SHA, sha);
    Map<String, Object> run = awaitTerminalRun(repoId);

    // 409, not a cheerful 202: a finished run has nothing to stop.
    given().when().post("/ci/api/runs/" + run.get("id") + "/cancel").then().statusCode(409);
    given().when().post("/ci/api/runs/no-such-run/cancel").then().statusCode(404);
  }

  @Test
  public void cancelAcceptsAnOptionalReason() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-cancel-reason", CONFIG_GREEN);
    CompletableFuture<String> started = new CompletableFuture<>();
    CountDownLatch release = new CountDownLatch(1);
    fakeRunner.during(
        0,
        spec -> {
          started.complete(spec.runId());
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    announcePush(repoId, "ci-cancel-reason", ZERO_SHA, sha);
    String runId = started.get(10, TimeUnit.SECONDS);

    given()
        .contentType("application/json")
        .body("{\"reason\":\"superseded manually\"}")
        .when()
        .post("/ci/api/runs/" + runId + "/cancel")
        .then()
        .statusCode(202);
    release.countDown();
    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("superseded manually", run.get("cancellationReason"));
  }

  @Test
  public void malformedConfigRecordsAConfigErrorRun() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-broken", "steps: [unclosed\n");
    announcePush(repoId, "ci-broken", ZERO_SHA, sha);

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
    announcePush(repoId, "ci-silent", ZERO_SHA, sha);

    Thread.sleep(1500); // grace for the (absent) async run to have appeared
    assertEquals(0, listRuns(repoId).size(), "a config-less push must record nothing");
  }

  @Test
  public void forcePushRecordsOneRunForTheSurvivingTip() throws Exception {
    // A force-push is one received ref update, so it yields exactly one run — for the tip that
    // exists. (A commit the repository no longer holds at all is covered where it can be staged:
    // HttpGitConfigSourceTest.aCommitTheRepositoryDoesNotHoldIsGone and CiRunServiceTest's GONE
    // cases.)
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
    announcePush(repoId, "ci-rewritten", replaced, sha);

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
    announcePush(repoId, "ci-limit-1", ZERO_SHA, first);
    awaitRunCount(repoId, 1);
    String second = pushBranchWithConfig(repoId, "ci-limit-2", CONFIG_GREEN);
    announcePush(repoId, "ci-limit-2", ZERO_SHA, second);
    awaitRunCount(repoId, 2);
    String third = pushBranchWithConfig(repoId, "ci-limit-3", CONFIG_GREEN);
    announcePush(repoId, "ci-limit-3", ZERO_SHA, third);
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

    announcePush(busy, "ci-repos-a", ZERO_SHA, pushBranchWithConfig(busy, "ci-repos-a", CONFIG_GREEN));
    awaitRunCount(busy, 1);
    announcePush(busy, "ci-repos-b", ZERO_SHA, pushBranchWithConfig(busy, "ci-repos-b", CONFIG_GREEN));
    awaitRunCount(busy, 2);
    announcePush(
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

    announcePush(busy, "ci-active-1", ZERO_SHA, busySha);
    String runningId = inStepZero.get(30, TimeUnit.SECONDS);
    // The intake writes the row before it answers, so this run is on the record the moment the 202
    // lands — no polling needed for it to be findable.
    announcePush(waiting, "ci-active-2", ZERO_SHA, waitingSha);

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
              .noneMatch(
                  run ->
                      List.of("SUCCESS", "FAILED", "CONFIG_ERROR", "TIMED_OUT")
                          .contains(run.get("status"))),
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
  public void theFinishedRouteIsTheListingAndNotASingleRunNamedFinished() {
    // The second literal under /runs/{runId}, and it inherits /active's hazard exactly: a ranking
    // regression would surface as a client's rail 404ing and nothing else, so it is asserted.
    given()
        .when()
        .get("/ci/api/runs/finished")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("$", org.hamcrest.Matchers.hasKey("runs"))
        .body("id", org.hamcrest.Matchers.nullValue())
        .body("commitSha", org.hamcrest.Matchers.nullValue());
  }

  @Test
  public void theFinishedListingIsTheNewestFiveByDefaultAndCarriesNoStepOutput() throws Exception {
    // Six, so the default is provably a bound rather than however many rows happen to exist.
    String repoId = seedOrigin();
    List<String> pushedInOrder = new java.util.ArrayList<>();
    for (int n = 1; n <= 6; n += 1) {
      String branch = "ci-fin-" + n;
      announcePush(repoId, branch, ZERO_SHA, pushBranchWithConfig(repoId, branch, CONFIG_GREEN));
      // Newest first, so the head of this repository's listing is the run just pushed. The row
      // exists the moment the intake answers, which is why the wait for it to *finish* is separate.
      pushedInOrder.add((String) awaitRunCount(repoId, n).get(0).get("id"));
    }
    awaitAllTerminal(repoId, 6);

    List<Map<String, Object>> finished = listFinishedRuns(null);
    assertEquals(5, finished.size(), "no limit means the newest five, not every finished run");

    // These six are the newest runs on the instance, so the answer's head is the last five of them,
    // newest first — the platform-wide ordering, across a listing nothing scoped to a repository.
    List<String> expected =
        List.of(
            pushedInOrder.get(5),
            pushedInOrder.get(4),
            pushedInOrder.get(3),
            pushedInOrder.get(2),
            pushedInOrder.get(1));
    assertEquals(expected, finished.stream().map(run -> run.get("id")).toList());

    // The list shape, exactly as the other two listings: no step output, no live object.
    assertNull(finished.get(0).get("steps"), "the finished listing must not carry step output");
    assertNull(finished.get(0).get("live"));
    assertNotNull(finished.get(0).get("finishedAt"), "a finished run has a finish");
    assertTrue(
        finished.stream().noneMatch(run -> ACTIVE.contains(run.get("status"))),
        "nothing in flight belongs in the finished listing");
  }

  @Test
  public void theTwoListingsPartitionTheRunsAndARunMovesFromOneToTheOther() throws Exception {
    // The complement claim, over HTTP and at one instant: a run is in exactly one of these lists,
    // and finishing is what moves it. Staged on a genuinely occupied worker for the same reason the
    // active listing's test is — that is what makes RUNNING and QUEUED real at a moment we control.
    String busy = seedOrigin();
    String waiting = seedOrigin();
    String busySha = pushBranchWithConfig(busy, "ci-part-1", CONFIG_GREEN);
    String waitingSha = pushBranchWithConfig(waiting, "ci-part-2", CONFIG_GREEN);

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

    announcePush(busy, "ci-part-1", ZERO_SHA, busySha);
    String runningId = inStepZero.get(30, TimeUnit.SECONDS);
    announcePush(waiting, "ci-part-2", ZERO_SHA, waitingSha);

    String queuedId;
    try {
      List<Map<String, Object>> active = listActiveRuns();
      queuedId = (String) runIn(active, waiting).get("id");
      List<String> finishedIds = ids(listFinishedRuns("100"));

      assertFalse(finishedIds.contains(runningId), "a RUNNING run has not finished");
      assertFalse(finishedIds.contains(queuedId), "a QUEUED run has not finished");
      // Nothing is in both lists, which is what "complement" has to mean to be worth relying on.
      assertTrue(
          ids(active).stream().noneMatch(finishedIds::contains),
          "no run is both in flight and finished");
    } finally {
      release.countDown();
    }

    awaitTerminalRun(busy);
    awaitTerminalRun(waiting);

    // Both have crossed over: gone from the active listing, arrived in the finished one.
    List<String> nowFinished = ids(listFinishedRuns("100"));
    assertTrue(nowFinished.contains(runningId), "a finished run arrives in the finished listing");
    assertTrue(nowFinished.contains(queuedId));
    assertTrue(
        ids(listActiveRuns()).stream().noneMatch(id -> id.equals(runningId) || id.equals(queuedId)),
        "a terminal run leaves the active listing");
  }

  @Test
  public void theFinishedLimitIsBoundedAboveAndRejectedBelow() {
    // Same parser and same envelope as the run listing's limit — one rule on one surface. "abc" is
    // again the one that would be a 404 if the parameter were bound to an Integer.
    for (String bad : List.of("0", "-1", "abc", "1.5", "9999999999999999999")) {
      given()
          .when()
          .get("/ci/api/runs/finished?limit=" + bad)
          .then()
          .statusCode(400)
          .body("message", org.hamcrest.Matchers.equalTo("Invalid limit"));
    }

    // An empty value reads as absent, exactly as it does on the run listing.
    assertTrue(listFinishedRuns("").size() <= 5, "an empty value reads as absent, so the default");
    // An over-large ask is capped rather than refused — this endpoint is unscoped, so an unbounded
    // limit would be the listing of every run on the instance that the surface deliberately lacks.
    assertTrue(listFinishedRuns("1000").size() <= 100, "the answer is capped at a hundred");
    assertEquals(1, listFinishedRuns("1").size(), "a limit under the row count is the bound");
  }

  private static List<String> ids(List<Map<String, Object>> runs) {
    return runs.stream().map(run -> (String) run.get("id")).toList();
  }

  /** Deadline-polls until a repository holds {@code expected} runs and none of them is in flight. */
  private void awaitAllTerminal(String repoId, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = listRuns(repoId);
      if (runs.size() == expected && runs.stream().noneMatch(r -> ACTIVE.contains(r.get("status")))) {
        return;
      }
      Thread.sleep(100);
    }
    fail("no " + expected + " finished CI runs for " + repoId + " within the deadline");
  }

  /** {@code limit} null omits the parameter entirely; {@code ""} sends it with no value. */
  private List<Map<String, Object>> listFinishedRuns(String limit) {
    return given()
        .when()
        .get("/ci/api/runs/finished" + (limit == null ? "" : "?limit=" + limit))
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .extract()
        .jsonPath()
        .getList("runs");
  }

  @Test
  public void theRepositorySummaryCarriesTheNewestRunAndTheNewestMainRun() throws Exception {
    String bothBranches = seedOrigin();
    String featureOnly = seedOrigin();

    // main first, then a newer run on another branch — so lastRun and lastMainRun differ and the
    // endpoint has to answer two different questions about one repository.
    announcePush(
        bothBranches, "main", ZERO_SHA, pushBranchWithConfig(bothBranches, "main", CONFIG_GREEN));
    awaitRunCount(bothBranches, 1);
    announcePush(
        bothBranches,
        "ci-summary-feature",
        ZERO_SHA,
        pushBranchWithConfig(bothBranches, "ci-summary-feature", CONFIG_GREEN));
    List<Map<String, Object>> bothRuns = awaitRunCount(bothBranches, 2);

    announcePush(
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

  // --- the push, as qits-githost announces it ---

  /**
   * Hands the listener one {@code SCMPublishCommit}. Synchronous through to the accepted row, which
   * is what the POST it replaced was too — so every case below keeps its timing.
   */
  private void announcePush(String repoId, String branch, String oldSha, String newSha) {
    pushes.onFrame(ScmPushFrames.push(repoId, branch, oldSha, newSha));
  }

  // --- git plumbing (StubGitHost serves these bares as <base>/git/<repoId>) ---

  /** The directory this suite's {@code qits.ci.git-host-url} points at. */
  private Path gitHostRoot() {
    return StubGitHost.ROOT.resolve("git");
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
