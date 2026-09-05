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
import eu.wohlben.qits.ci.bus.CiEventTriggerListener;
import eu.wohlben.qits.ci.bus.ScmPushFrames;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.githost.FakeGitHostRepoListing;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole MVP loop at the seams this repo owns (docs/epics/qits-ci/): a domain event reaches the
 * trigger engine, ci lists {@code .config/qits/} at the repository's {@code main}, reads the {@code
 * ci-event-*.yml} that selects the event, and drives the steps it declares through the step seam —
 * asserted through the public read surface. Docker-free: only the step seam is faked (by {@code
 * eu.wohlben.qits.ci.control.FakeCiStepRunner} in this module's test sources).
 *
 * <p><b>What a step "did" is scripted, not performed.</b> The fake used to clone and run the script
 * as host processes, so assertions here could read a committed file back out of a step's output;
 * that fake died with the approach it modelled, because qits-ci never executes a repository's code.
 * What survives at this level is everything between the intake and the read surface — which config
 * an event produced, how many steps it declared, what each step was asked to run, and how outcomes
 * become rows. What a real container does with a real script is {@code CiDaemonGateIT}'s job.
 *
 * <p><b>Where the loop starts, and it moved on 2026-09-05.</b> It used to start at a push: this
 * class seeded a bare origin, committed {@code .config/qits/ci-post-receive.yml} on a branch, pushed
 * it, and handed {@code ScmPublishCommitListener} the {@code SCMPublishCommit} qits-githost
 * publishes. <b>The platform runs no CI outside release requests</b>, so that intake retired — an
 * ordinary push triggers nothing at all — and every case here now drives the trigger type that is
 * left: a trigger file committed on {@code main} that selects an event, and {@code POST
 * /ci/api/events/trigger} supplying one. The pipelines, the outcomes and the read surface are
 * unchanged, which is the point of asserting them here rather than at the intake.
 *
 * <p>The old starting point has one case left, and it is a <b>pinning</b> rather than a leftover:
 * {@link #anOrdinaryPushIsConsumedAndTriggersNothing} hands a real {@code SCMPublishCommit} to the
 * generic engine and asserts no run appears. The event still reaches trigger evaluation — a
 * repository that declares {@code event: SCMPublishCommit} is served by the ordinary grammar, which
 * is a capability that stays — and no repository declares one, so nothing runs.
 *
 * <p>The monorepo's version drove a real {@code git push} and let the hook fire; assertions about
 * the host's own filtering (a branch deletion must not produce a build) belong to qits-githost.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
public class CiPipelineBoundaryTest {

  /** The all-zero sha git reports as the old id of a newly created branch. */
  private static final String ZERO_SHA = "0".repeat(40);

  /** The two statuses a run can hold before it is finished. */
  private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING");

  /** The one trigger file every repository here commits, on {@code main}. */
  private static final String TRIGGER_PATH = ".config/qits/ci-event-boundary.yml";

  /** This class's own event name, so no other suite's trigger file can be fired by one of these. */
  private static final String EVENT_NAME = "CiBoundaryEvent";

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

  @Inject FakeGitHostRepoListing gitHostListing;

  /** The bus end of the trigger engine, for the one case that is about what a push does NOT do. */
  @Inject CiEventTriggerListener triggers;

  /**
   * Every repository this test method seeded. A repository becomes a candidate through the git
   * host's listing — it has no run history to be known by until one of these events fires — and the
   * listing is set as a whole, so the ids accumulate here rather than being passed one at a time.
   */
  private final List<String> seeded = new ArrayList<>();

  @BeforeEach
  void resetRunner() {
    fakeRunner.reset();
    seeded.clear();
    gitHostListing.set();
  }

  @Test
  public void anEventWithATriggerFileRecordsAGreenRunWithStepOutputs() throws Exception {
    String repoId = seedOrigin();
    String sha = runOn(repoId, "ci-green");

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
    String repoId =
        seedOrigin(
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
    runOn(repoId, "ci-red");

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
    runOn(repoId, "ci-done");
    Map<String, Object> run = awaitTerminalRun(repoId);

    // 409, not a cheerful 202: a finished run has nothing to stop.
    given().when().post("/ci/api/runs/" + run.get("id") + "/cancel").then().statusCode(409);
    given().when().post("/ci/api/runs/no-such-run/cancel").then().statusCode(404);
  }

  @Test
  public void cancelAcceptsAnOptionalReason() throws Exception {
    String repoId = seedOrigin();
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
    runOn(repoId, "ci-cancel-reason");
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
  public void aTriggerFileThatCannotBeParsedRecordsNoRun() throws Exception {
    // This used to be `malformedConfigRecordsAConfigErrorRun`, and the outcome changed with the
    // intake rather than with the rule. The push path read its config on the RUN worker, so a
    // pipeline that would not parse was a row already accepted and had to be finished as
    // CONFIG_ERROR — "a broken gate is visible rather than silently green". A trigger file is read
    // and parsed by the engine BEFORE any row exists, so a broken one is a WARN and no run at all,
    // and nothing writes CONFIG_ERROR any more. Visible in the log rather than on the surface,
    // which is what a file that declares no runnable pipeline can honestly produce.
    String repoId = seedOrigin("steps: [unclosed\n");
    runOn(repoId, "ci-broken");

    Thread.sleep(1500); // grace for the (absent) async run to have appeared
    assertEquals(0, listRuns(repoId).size(), "an unparseable trigger file must record nothing");
  }

  @Test
  public void aRepositoryDeclaringNoTriggerFileRecordsNoRun() throws Exception {
    // Opt-in, at the level it now lives: a repository with no `.config/qits/ci-event-*.yml` is asked
    // on every arriving event and answers nothing. It must not accumulate a row per event.
    String repoId = seedOriginWithoutTrigger();
    String sha = pushBranch(repoId, "ci-silent");
    trigger(repoId, "ci-silent", sha);

    Thread.sleep(1500); // grace for the (absent) async run to have appeared
    assertEquals(0, listRuns(repoId).size(), "a repository declaring nothing must record nothing");
  }

  @Test
  public void anOrdinaryPushIsConsumedAndTriggersNothing() throws Exception {
    // THE ruling, pinned at the seam it used to be broken at: the platform runs no CI outside
    // release requests, so an ordinary push triggers nothing. This is a real SCMPublishCommit — the
    // bytes qits-githost publishes, built from the record itself (bus/ScmPushFrames) — handed to the
    // generic trigger engine, which is the arm that STAYS. It reaches evaluation like every other
    // event and matches nothing, because no repository declares `event: SCMPublishCommit`.
    //
    // What used to happen instead: ScmPublishCommitListener accepted one run per pushed branch ref,
    // against a ci-post-receive.yml that had been deleted everywhere — thirteen phantom QUEUED rows
    // on 2026-09-05, each holding a runner slot to discover that nothing was declared.
    String repoId = seedOrigin();
    String sha = pushBranch(repoId, "ci-pushed");

    triggers.onFrame(ScmPushFrames.push(repoId, "ci-pushed", ZERO_SHA, sha));

    Thread.sleep(1500); // grace for a run to have appeared, if anything had accepted one
    assertEquals(0, listRuns(repoId).size(), "an ordinary push records no run");
    assertEquals(
        List.of(),
        listActiveRuns().stream().filter(run -> repoId.equals(run.get("repoId"))).toList(),
        "and nothing is queued for it either");
    // The repository's own trigger file still fires on its own event, so what is asserted above is
    // "this push matched nothing" rather than "this repository cannot run".
    runOn(repoId, "ci-pushed-then-triggered");
    assertEquals("SUCCESS", awaitTerminalRun(repoId).get("status"));
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
    // Three runs on one repository, fired in order, so "newest" is a fact rather than a tie.
    runOn(repoId, "ci-limit-1");
    awaitRunCount(repoId, 1);
    runOn(repoId, "ci-limit-2");
    awaitRunCount(repoId, 2);
    runOn(repoId, "ci-limit-3");
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
    String neverRun = seedOrigin();

    runOn(busy, "ci-repos-a");
    awaitRunCount(busy, 1);
    runOn(busy, "ci-repos-b");
    awaitRunCount(busy, 2);
    runOn(quiet, "ci-repos-c");
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
    assertFalse(ids.contains(neverRun), "a repository with no run must not be listed");
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
    String busySha = pushBranch(busy, "ci-active-1");
    String waitingSha = pushBranch(waiting, "ci-active-2");

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

    trigger(busy, "ci-active-1", busySha);
    String runningId = inStepZero.get(30, TimeUnit.SECONDS);
    // The accept writes the row before the endpoint answers, so this run is on the record the moment
    // the 200 lands — no polling needed for it to be findable.
    trigger(waiting, "ci-active-2", waitingSha);

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
    List<String> firedInOrder = new ArrayList<>();
    for (int n = 1; n <= 6; n += 1) {
      runOn(repoId, "ci-fin-" + n);
      // Newest first, so the head of this repository's listing is the run just fired. The row exists
      // the moment the accept answers, which is why the wait for it to *finish* is separate.
      firedInOrder.add((String) awaitRunCount(repoId, n).get(0).get("id"));
    }
    awaitAllTerminal(repoId, 6);

    List<Map<String, Object>> finished = listFinishedRuns(null);
    assertEquals(5, finished.size(), "no limit means the newest five, not every finished run");

    // These six are the newest runs on the instance, so the answer's head is the last five of them,
    // newest first — the platform-wide ordering, across a listing nothing scoped to a repository.
    List<String> expected =
        List.of(
            firedInOrder.get(5),
            firedInOrder.get(4),
            firedInOrder.get(3),
            firedInOrder.get(2),
            firedInOrder.get(1));
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
    String busySha = pushBranch(busy, "ci-part-1");
    String waitingSha = pushBranch(waiting, "ci-part-2");

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

    trigger(busy, "ci-part-1", busySha);
    String runningId = inStepZero.get(30, TimeUnit.SECONDS);
    trigger(waiting, "ci-part-2", waitingSha);

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
    runOn(bothBranches, "main");
    awaitRunCount(bothBranches, 1);
    runOn(bothBranches, "ci-summary-feature");
    List<Map<String, Object>> bothRuns = awaitRunCount(bothBranches, 2);

    runOn(featureOnly, "ci-summary-only");
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
    assertEquals("EVENT", lastRun.get("triggerType"));
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

  // --- the event, as the trigger engine is given one ---

  /**
   * Fires this class's event for one repository and branch, and returns the sha the run is recorded
   * at. Synchronous through to the accepted row: {@code POST /ci/api/events/trigger} evaluates
   * before it answers, which is what lets every case below keep the timing the push intake had.
   *
   * <p>The branch is created and pushed first, so the commit the trigger's {@code checkout:} names
   * really exists on the host — the run clones it.
   */
  private String runOn(String repoId, String branch) throws Exception {
    String sha = pushBranch(repoId, branch);
    trigger(repoId, branch, sha);
    return sha;
  }

  /** The same, without firing anything: what a repository looks like before its event arrives. */
  private String pushBranch(String repoId, String branch) throws Exception {
    Path clone = cloneRepo(repoId);
    if (!"main".equals(branch)) {
      git(clone, "checkout", "-q", "-b", branch);
    }
    // A second push to one repository would otherwise be an empty commit, which git refuses — and a
    // repository needs several runs to have a history worth reading.
    Files.writeString(clone.resolve("branch.txt"), branch + " " + UUID.randomUUID() + "\n");
    commitAll(clone, branch);
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", branch);
    return sha;
  }

  /** One event, carrying the three fields this class's trigger files select and check out on. */
  private void trigger(String repoId, String branch, String sha) {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"name":"%s","occurredAt":"2026-09-05T09:00:00Z",\
            "payload":{"boundaryRepo":"%s","branch":"%s","sha":"%s"}}"""
                .formatted(EVENT_NAME, repoId, branch, sha))
        .when()
        .post("/ci/api/events/trigger")
        .then()
        .statusCode(200);
  }

  // --- git plumbing (StubGitHost serves these bares as <base>/git/<repoId>) ---

  /** The directory this suite's {@code qits.ci.git-host-url} points at. */
  private Path gitHostRoot() {
    return StubGitHost.ROOT.resolve("git");
  }

  private String seedOrigin() throws Exception {
    return seedOrigin(CONFIG_GREEN);
  }

  /**
   * Seeds a bare origin at {@code <git-host>/git/<repoId>} holding {@code hello.txt} and, on {@code
   * main}, the trigger file whose pipeline is {@code steps} — built here rather than cloned from a
   * fixture, so the suite needs no submodule.
   *
   * <p><b>The selection names this repository and nothing else</b>, because every repository this
   * JVM has ever seeded is a candidate for every event evaluated in it: a file selecting on a shared
   * literal would let one test's event fire another's repository, and "exactly one run" would stop
   * being a statement about the test making it. {@code boundaryRepo} rather than {@code repoId},
   * which the engine reads against the candidate's own identity rather than as a payload field.
   *
   * <p><b>{@code checkout:} is what puts a run on a branch at all.</b> Discovery and selection read
   * {@code main}'s head — a pushed branch cannot alter the CI that gates it — and only the recorded
   * run's branch and sha come from the payload, which is exactly the shape a release request's QA
   * pipeline uses.
   */
  private String seedOrigin(String steps) throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path seed = Files.createTempDirectory("ci-boundary-seed");
    git(seed, "init", "-q", "-b", "main");
    Files.writeString(seed.resolve("hello.txt"), "hello\n");
    Path triggerFile = seed.resolve(TRIGGER_PATH);
    Files.createDirectories(triggerFile.getParent());
    Files.writeString(triggerFile, triggerFile(repoId, steps));
    commitAll(seed, "initial");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    seeded.add(repoId);
    gitHostListing.set(seeded.toArray(String[]::new));
    return repoId;
  }

  /** The same, for a repository that declares no trigger file at all. */
  private String seedOriginWithoutTrigger() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path seed = Files.createTempDirectory("ci-boundary-seed");
    git(seed, "init", "-q", "-b", "main");
    Files.writeString(seed.resolve("hello.txt"), "hello\n");
    commitAll(seed, "initial");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    seeded.add(repoId);
    gitHostListing.set(seeded.toArray(String[]::new));
    return repoId;
  }

  private static String triggerFile(String repoId, String steps) {
    return """
        event: %s
        when:
          - boundaryRepo: { exact: %s }
        checkout:
          branch: branch
          sha: sha
        %s"""
        .formatted(EVENT_NAME, repoId, steps);
  }

  private Path cloneRepo(String repoId) throws Exception {
    Path clone = Files.createTempDirectory("ci-boundary-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitHostRoot().resolve(repoId).toString(), clone.toString());
    return clone;
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
