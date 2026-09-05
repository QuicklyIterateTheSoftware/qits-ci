package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /ci/api/events/trigger} at the service boundary: the same evaluation the bus drives,
 * with the event supplied by the caller.
 *
 * <p>It is the second inbound adapter of one seam, so what is under test here is the adapter and
 * nothing beneath it — that an ordinary trigger file fires, that the snapshot the run carries is the
 * one the request named, that the id decides whether a repeat is a rerun or a no-op, and <b>what the
 * answer promises</b>: 200 with the rows it wrote, never 202 for work handed to a queue. The bus
 * half of the same engine is {@code bus/CiEventTriggerCausationTest}; selection semantics belong to
 * the {@code ci} module's own suite, and so does the 503 (no candidate readable), which cannot be
 * staged here because this instance has a live git host and other classes' repositories on it.
 *
 * <p>It runs with the shipped {@code %test} configuration — the event bus dark, the machine gate off
 * — so it shares a Quarkus instance with {@code CiPipelineBoundaryTest} rather than costing a
 * restart. The guard is {@code MachineGuardTest}'s to hold, which is where the gate is on.
 *
 * <p><b>Every repository this JVM has ever seeded is a candidate for every event evaluated in it.</b>
 * So each trigger file below selects an upstream id minted per test method: a selection on a shared
 * literal would let one method's event fire another's repository, and "exactly one run" would stop
 * being a statement about the test making it.
 *
 * <p><b>A repository becomes a candidate through the git host's LISTING here, and it used to become
 * one by being pushed.</b> Each method opened with one push, whose accepted run put the repository
 * into {@code KnownCiRepos} — a candidate list built from recorded runs, which was the whole reason
 * the listing had to be added (a platform seeded straight onto the git host could not event-trigger
 * at all). Per-push CI retired on 2026-09-05, so a push records nothing and cannot make a candidate
 * of anything; the listing is what does it, and the case that was written to prove the gap closed is
 * now simply how every case here works.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
public class CiManualTriggerTest {

  private static final String TRIGGER_PATH = ".config/qits/ci-event-manual.yml";

  private static final String TRIGGER = "/ci/api/events/trigger";

  @Inject FakeCiStepRunner fakeRunner;

  @Inject FakeGitHostRepoListing gitHostListing;

  @BeforeEach
  void resetFakes() {
    fakeRunner.reset();
    // Empty by default, so a repository another method seeded is in nobody else's candidate set
    // until the method that wants it says so.
    gitHostListing.set();
  }

  @Test
  public void aSuppliedEventRunsTheTriggerFileThatSelectsIt() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    makeCandidate(repoId);

    String eventId =
        trigger(
            """
            {"name":"SoftwareRelease","occurredAt":"2026-08-04T09:00:00Z","payload":%s}"""
                .formatted(payload(upstream)));
    assertEquals(eventId, UUID.fromString(eventId).toString(), "the id comes back canonical");

    Map<String, Object> run = awaitEventRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(eventId, run.get("triggerEventId"), "the id the caller was handed to correlate on");
    assertEquals("SoftwareRelease", run.get("triggerEventName"));
    assertEquals(TRIGGER_PATH, run.get("configPath"));
    assertEquals("main", run.get("branch"), "an event names no ref, so the tracked branch supplies one");

    // The snapshot reaches the step container exactly as a bus arrival's would — which is also how
    // the two columns the run listing does not expose are read from outside.
    Map<String, String> env = envOf(run);
    assertEquals(eventId, env.get("QITS_EVENT_ID"));
    assertEquals("SoftwareRelease", env.get("QITS_EVENT_NAME"));
    assertEquals("2026-08-04T09:00:00Z", env.get("QITS_EVENT_OCCURRED_AT"));
    assertEquals(payload(upstream), env.get("QITS_EVENT_PAYLOAD"), "the bytes the caller sent");
  }

  @Test
  public void anOmittedOccurredAtDefaultsToNow() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    makeCandidate(repoId);

    long before = System.currentTimeMillis();
    trigger("""
        {"name":"SoftwareRelease","payload":%s}""".formatted(payload(upstream)));

    Map<String, String> env = envOf(awaitEventRun(repoId));
    long stamped = Instant.parse(env.get("QITS_EVENT_OCCURRED_AT")).toEpochMilli();
    assertTrue(stamped >= before - 1_000, "defaulted to now, got " + env.get("QITS_EVENT_OCCURRED_AT"));
  }

  @Test
  public void theSameExplicitIdTwiceRunsOnce() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    makeCandidate(repoId);

    String eventId = UUID.randomUUID().toString();
    String body =
        """
        {"name":"SoftwareRelease","eventId":"%s","payload":%s}"""
            .formatted(eventId, payload(upstream));
    assertEquals(eventId, trigger(body), "an explicit id is used verbatim");
    awaitEventRun(repoId);

    // The dedupe constraint is (trigger_event_id, repo_id, config_path), so the second call is
    // dropped as already-triggered. That is what makes an explicit id safe in a bootstrap script.
    assertEquals(eventId, trigger(body));
    Thread.sleep(1_500);
    assertEquals(1, eventRunsOf(repoId).size(), "a replayed id records no second run");
  }

  @Test
  public void twoDefaultedIdsForOnePayloadRunTwice() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    makeCandidate(repoId);

    String body = """
        {"name":"SoftwareRelease","payload":%s}""".formatted(payload(upstream));
    String first = trigger(body);
    awaitEventRun(repoId);
    String second = trigger(body);
    assertNotEquals(first, second, "a fresh id per call is what makes a rerun possible at all");

    List<Map<String, Object>> runs = awaitEventRuns(repoId, 2);
    assertEquals(
        List.of(first, second).stream().sorted().toList(),
        runs.stream().map(r -> (String) r.get("triggerEventId")).sorted().toList());
  }

  @Test
  public void aRepositoryOnlyTheGitHostListsTriggersWithNoRunHistoryAtAll() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);

    // The production gap this endpoint exists for: no run row is precisely what KnownCiRepos answers
    // "not a candidate" to, so before the git host grew a listing a repository seeded straight onto
    // it could not event-trigger at all. Asserted explicitly here, though every case in this file
    // now takes the same route — a push records nothing to be known by.
    assertTrue(runsOf(repoId).isEmpty(), "the repository has no run history in qits-ci");

    makeCandidate(repoId);

    String eventId =
        trigger(
            """
            {"name":"SoftwareRelease","occurredAt":"2026-08-05T09:00:00Z","payload":%s}"""
                .formatted(payload(upstream)));

    Map<String, Object> run = awaitRuns(repoId, 1).get(0);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals("EVENT", run.get("triggerType"), "the listing made it a candidate, nothing else");
    assertEquals(eventId, run.get("triggerEventId"));
    assertEquals(TRIGGER_PATH, run.get("configPath"));
  }

  // --- what a 200 promises, and why it is not a 202 ---

  /**
   * <b>The answer is what the call did, not that it was taken.</b> This endpoint used to hand the
   * event to the trigger worker's bounded queue and answer 202 whatever came back — and on
   * 2026-08-10 a bootstrap's release replay was answered 2xx for an event that was never evaluated.
   * A bus frame survives that (unevaluated, it stays owed and the next catch-up sweep offers it
   * again); a caller-supplied event is on no log and nothing will ever offer it again. So the
   * evaluation happens before the answer, and the answer names the rows it wrote.
   */
  @Test
  public void theAnswerNamesTheRunsItRecorded() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    makeCandidate(repoId);

    JsonPath answer =
        triggerResult("""
            {"name":"SoftwareRelease","payload":%s}""".formatted(payload(upstream)));

    List<String> runIds = answer.getList("runIds");
    assertEquals(1, runIds.size(), "one trigger file selected the event, so one run");
    assertTrue(answer.getInt("repositoriesRead") >= 1, "it really asked somebody");
    // The row exists as the call returns. Nothing is polled for here on purpose: that is the
    // difference between this and the 202 it replaced.
    List<Map<String, Object>> recorded = eventRunsOf(repoId);
    assertEquals(1, recorded.size());
    assertEquals(runIds.get(0), recorded.get(0).get("id"));
    assertEquals(answer.getString("eventId"), recorded.get(0).get("triggerEventId"));
    awaitRuns(repoId, 1);
  }

  @Test
  public void anEventNothingSelectsAnswersTwoHundredWithNoRuns() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    makeCandidate(repoId);

    JsonPath answer =
        triggerResult(
            """
            {"name":"NothingDeclaresThisEvent","payload":%s}""".formatted(payload(upstream)));

    // "Asked somebody, matched none of them", which a 503 is what says the opposite of — see
    // MachineGuardTest, where there is no git host to ask. Only the read count is asserted here: the
    // shared instance carries every other class's repositories, so the skipped list is not this
    // test's to predict. It is pinned exactly in the engine's own suite.
    assertEquals(List.of(), answer.getList("runIds"));
    assertTrue(answer.getInt("repositoriesRead") >= 1);
    assertEquals(List.of(), eventRunsOf(repoId));
  }

  @Test
  public void badInputIs400WithTheMessageEnvelope() {
    assertBadRequest("""
        {"name":"  ","payload":{}}""");
    assertBadRequest("""
        {"name":"SoftwareRelease"}""");
    assertBadRequest("""
        {"name":"SoftwareRelease","payload":{},"occurredAt":"yesterday"}""");
    assertBadRequest("""
        {"name":"SoftwareRelease","payload":{},"eventId":"not-a-uuid"}""");
    // A payload that is not an object cannot carry a dot-path, so it is refused rather than walked.
    assertBadRequest("""
        {"name":"SoftwareRelease","payload":"just a string"}""");
  }

  private static void assertBadRequest(String body) {
    String message =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when()
            .post(TRIGGER)
            .then()
            .statusCode(400)
            .extract()
            .path("message");
    assertTrue(message != null && !message.isBlank(), "CiExceptionMapper's envelope, not a stack");
  }

  // --- the request, and what it selects ---

  private static String upstream() {
    return "up-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** A SoftwareRelease payload, in the alphabetical key order the canonical wire form uses. */
  private static String payload(String upstream) {
    return "{\"packageName\":\"@qits/ui-components\",\"packageType\":\"npm\",\"repository\":\""
        + upstream
        + "\",\"version\":\"1.4.0\"}";
  }

  private static String triggerFile(String upstream) {
    return """
        event: SoftwareRelease
        when:
          - repository: { exact: %s }
        steps:
          - image: alpine:3
            script: echo bump
        """
        .formatted(upstream);
  }

  /** POSTs the trigger and returns the whole answer — 200, because the call evaluates before it answers. */
  private static JsonPath triggerResult(String body) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  /** POSTs the trigger and returns the event id it answered with. */
  private static String trigger(String body) {
    return triggerResult(body).getString("eventId");
  }

  // --- plumbing, the shape CiEventTriggerCausationTest uses ---

  /**
   * Makes the repository a candidate: qits-ci evaluates an event only against repositories it can
   * name, and the git host's listing is what names one it has recorded no run for.
   */
  private void makeCandidate(String repoId) {
    gitHostListing.set(repoId);
  }

  private String seedOrigin(String upstream) throws Exception {
    String repoId = "man-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Path seed = Files.createTempDirectory("ci-manual-trigger-seed");
    git(seed, "init", "-q", "-b", "main");
    write(seed, TRIGGER_PATH, triggerFile(upstream));
    git(seed, "add", ".");
    git(seed, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", "ci config");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  private static void write(Path root, String path, String content) throws Exception {
    Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private Path gitHostRoot() {
    return StubGitHost.ROOT.resolve("git");
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

  private Map<String, String> envOf(Map<String, Object> run) {
    return fakeRunner.executed().stream()
        .filter(spec -> spec.runId().equals(run.get("id")))
        .findFirst()
        .orElseGet(() -> fail("the triggered run executed no step"))
        .env();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> runsOf(String repoId) {
    return given()
        .when()
        .get("/ci/api/runs?repositoryId=" + repoId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("runs");
  }

  private List<Map<String, Object>> eventRunsOf(String repoId) {
    return runsOf(repoId).stream().filter(run -> "EVENT".equals(run.get("triggerType"))).toList();
  }

  private Map<String, Object> awaitEventRun(String repoId) throws Exception {
    return awaitEventRuns(repoId, 1).get(0);
  }

  private List<Map<String, Object>> awaitEventRuns(String repoId, int expected) throws Exception {
    awaitRuns(repoId, expected);
    return eventRunsOf(repoId);
  }

  /** Deadline-polls until the repository has {@code expected} runs and none of them is active. */
  private List<Map<String, Object>> awaitRuns(String repoId, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = runsOf(repoId);
      if (runs.size() >= expected
          && runs.stream()
              .noneMatch(r -> "QUEUED".equals(r.get("status")) || "RUNNING".equals(r.get("status")))) {
        return runs;
      }
      Thread.sleep(100);
    }
    return fail("no " + expected + " terminal CI runs for " + repoId + " within the deadline");
  }
}
