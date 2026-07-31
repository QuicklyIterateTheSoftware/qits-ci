package eu.wohlben.qits.ci.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.eventsourcing.control.EventDispatcher;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bus half of the trigger engine, in one JVM: a real frame down the raw path, a real
 * {@code git ls-tree} of a real bare repository, a real run, and the {@code parentId} on the PUT the
 * run publishes. It is the deployable's own wiring under test — the raw listener bean, the engine,
 * the widened announcer — where {@code CiEventTriggerServiceTest} in the {@code ci} module holds the
 * evaluation and the provenance against fakes.
 *
 * <p><b>The one thing only this class can assert is the causation edge.</b> The engine consumed the
 * frame on the caller's thread and enqueued; the run published minutes of wall-clock later on {@code
 * ci-run-worker}, where no {@code CausationScope} survives. So a {@code parentId} on that PUT equal
 * to the frame's id is the whole of Decision 7 proved end to end, and it is the platform's first
 * automatic causation edge.
 *
 * <p>Frames are handed to {@link EventDispatcher} directly rather than broadcast by the stub, the
 * same choice the eventsourcing suite makes: a routing claim is about dispatch, and only a claim
 * about the wire needs a socket. The one wire claim here — that the subscribe frame is {@code ["*"]}
 * — reads what the stub recorded when the application dialled it at startup.
 */
@QuarkusTest
@TestProfile(BuildSuccessfulPublishTest.EventsourcingOn.class)
@WithTestResource(StubEventsServer.class)
public class CiEventTriggerCausationTest {

  private static final String ZERO_SHA = "0".repeat(40);

  private static final String POST_RECEIVE = "steps:\n  - image: alpine:3\n    script: echo push\n";

  private static final String TRIGGER_PATH = ".config/qits/ci-event-upstream.yml";

  /**
   * The selection names an upstream id unique to the repository that committed it. That is not
   * decoration: every repository this JVM has ever seeded is a candidate for every frame, so a
   * trigger selecting a shared literal would make one test method's event fire the previous method's
   * repository — and "exactly two runs, exactly two publishes" would stop being about this test.
   */
  private static String trigger(String upstream) {
    return """
        event: BuildSuccessful
        when:
          - repoId: { exact: %s }
            branch: { exact: main }
        steps:
          - image: alpine:3
            script: echo bump
        """
        .formatted(upstream);
  }

  private final ObjectMapper json = new ObjectMapper();

  @ConfigProperty(name = "qits.ci.git-host-url")
  String gitHostUrl;

  @Inject FakeCiStepRunner fakeRunner;

  @Inject EventDispatcher dispatcher;

  @BeforeEach
  void resetState() {
    fakeRunner.reset();
    StubEventsServer.reset();
  }

  @Test
  public void theSubscribeFrameIsCollapsedToEverythingByTheTriggerEngine() {
    // The trigger engine's raw listener answers Set.of("*") permanently, and "*" anywhere in the
    // union collapses the whole frame. BuildSuccessfulListener therefore no longer names itself on
    // the wire and keeps working regardless, because dispatch filters and the wire never did.
    List<String> subscribes = StubEventsServer.subscribes();
    assertTrue(!subscribes.isEmpty(), "the application dials on startup because listeners exist");
    assertTrue(
        subscribes.get(0).contains("\"*\""),
        "expected the union to collapse to [\"*\"], got " + subscribes.get(0));
    assertTrue(
        !subscribes.get(0).contains("BuildSuccessful"),
        "\"*\" absorbs the typed signature rather than sitting beside it: " + subscribes.get(0));
  }

  @Test
  public void anEventTriggeredRunPublishesUnderTheEventThatCausedIt() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);

    // A push first: it makes the repository one qits-ci has heard of, which is what the shipped
    // candidate source means by a candidate — and it is also the root-event half of the assertion.
    postReceive(repoId, tipOf(repoId));
    Map<String, Object> pushed = awaitRuns(repoId, 1).get(0);
    assertEquals("POST_RECEIVE", pushed.get("triggerType"));
    assertEquals(".config/qits/ci-post-receive.yml", pushed.get("configPath"));
    assertEquals(null, pushed.get("triggerEventId"));

    List<StubEventsServer.Put> afterPush = awaitPuts(1);
    JsonNode rootEnvelope = json.readTree(afterPush.get(0).body());
    assertTrue(rootEnvelope.get("parentId").isNull(), "a push is not caused by an event");

    // Now the event. It arrives exactly as the socket would deliver it.
    String eventId = UUID.randomUUID().toString();
    dispatcher.dispatch(frame(eventId, upstream));

    List<Map<String, Object>> runs = awaitRuns(repoId, 2);
    Map<String, Object> triggered =
        runs.stream()
            .filter(run -> "EVENT".equals(run.get("triggerType")))
            .findFirst()
            .orElseGet(() -> fail("no event-triggered run was recorded"));
    assertEquals("SUCCESS", triggered.get("status"));
    assertEquals(eventId, triggered.get("triggerEventId"));
    assertEquals("BuildSuccessful", triggered.get("triggerEventName"));
    assertEquals(TRIGGER_PATH, triggered.get("configPath"));
    assertNotEquals(pushed.get("id"), triggered.get("id"));

    // The step container saw the whole event.
    Map<String, String> env =
        fakeRunner.executed().stream()
            .filter(spec -> spec.runId().equals(triggered.get("id")))
            .findFirst()
            .orElseGet(() -> fail("the triggered run executed no step"))
            .env();
    assertEquals(eventId, env.get("QITS_EVENT_ID"));
    assertEquals("BuildSuccessful", env.get("QITS_EVENT_NAME"));
    assertEquals("2026-07-31T12:46:03Z", env.get("QITS_EVENT_OCCURRED_AT"));
    assertEquals(payload(upstream), env.get("QITS_EVENT_PAYLOAD"));

    // And the edge: the run's OWN BuildSuccessful names the event that triggered it as its parent.
    List<StubEventsServer.Put> puts = awaitPuts(2);
    JsonNode envelope = json.readTree(puts.get(1).body());
    assertEquals("BuildSuccessful", envelope.get("name").asText());
    assertEquals(eventId, envelope.get("parentId").asText(), "the first automatic causation edge");

    // The parent is envelope data and must never have reached the compared payload bytes.
    JsonNode payload = json.readTree(envelope.get("payload").asText());
    assertTrue(!payload.has("parentId"), payload.toString());
    assertEquals(repoId, payload.get("repoId").asText());
  }

  @Test
  public void aRedeliveredFrameRecordsNoSecondRunAndPublishesNothingFurther() throws Exception {
    String upstream = upstream();
    String repoId = seedOrigin(upstream);
    postReceive(repoId, tipOf(repoId));
    awaitRuns(repoId, 1);
    awaitPuts(1);

    String eventId = UUID.randomUUID().toString();
    dispatcher.dispatch(frame(eventId, upstream));
    awaitRuns(repoId, 2);
    awaitPuts(2);

    // The same event again — legal on this bus, and the future catch-up feature will do it on
    // purpose. Observable from outside as "the run list did not grow".
    dispatcher.dispatch(frame(eventId, upstream));
    Thread.sleep(1_500);
    assertEquals(2, runsOf(repoId).size(), "a redelivered event is dropped, not re-run");
    assertEquals(2, StubEventsServer.puts().size(), "and so nothing further is published");
  }

  @Test
  public void anEventNoTriggerSelectsRecordsNothing() throws Exception {
    String repoId = seedOrigin(upstream());
    postReceive(repoId, tipOf(repoId));
    awaitRuns(repoId, 1);

    // A real BuildSuccessful, from a repository nothing declares a selection for.
    dispatcher.dispatch(frame(UUID.randomUUID().toString(), "a-repo-nobody-listens-for"));
    Thread.sleep(1_500);
    assertEquals(1, runsOf(repoId).size());
  }

  // --- the frame, as the socket would deliver it ---

  private static String upstream() {
    return "up-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  /** A canonical BuildSuccessful payload, in the alphabetical key order the wire uses. */
  private static String payload(String upstream) {
    return "{\"branch\":\"main\",\"commitSha\":\"cafebabe\",\"repoId\":\"" + upstream + "\"}";
  }

  /**
   * The frame verbatim, written out rather than serialized from a map: {@code description} and
   * {@code parentId} are explicit JSON nulls on this wire, which {@code Map.of} cannot hold.
   */
  private String frame(String eventId, String upstream) throws Exception {
    return "{\"id\":\""
        + eventId
        + "\",\"name\":\"BuildSuccessful\",\"occurredAt\":\"2026-07-31T12:46:03Z\",\"payload\":"
        + json.writeValueAsString(payload(upstream))
        + ",\"description\":null,\"parentId\":null}";
  }

  // --- plumbing, the same shape BuildSuccessfulPublishTest uses ---

  private void postReceive(String repoId, String newSha) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("repoId", repoId, "branch", "main", "oldSha", ZERO_SHA, "newSha", newSha))
        .when()
        .post("/ci/api/events/post-receive")
        .then()
        .statusCode(202);
  }

  /** A bare origin carrying both trigger types, so one repository exercises both paths. */
  private String seedOrigin(String upstream) throws Exception {
    String repoId = "trg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Path seed = Files.createTempDirectory("ci-trigger-seed");
    git(seed, "init", "-q", "-b", "main");
    write(seed, ".config/qits/ci-post-receive.yml", POST_RECEIVE);
    write(seed, TRIGGER_PATH, trigger(upstream));
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

  private String tipOf(String repoId) throws Exception {
    return git(gitHostRoot().resolve(repoId), "rev-parse", "main").trim();
  }

  private Path gitHostRoot() {
    return Path.of(gitHostUrl.replaceFirst("^file://", ""), "git");
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

  /** Deadline-polls until the repository has {@code expected} terminal runs. */
  private List<Map<String, Object>> awaitRuns(String repoId, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = runsOf(repoId);
      if (runs.size() >= expected && runs.stream().noneMatch(r -> "RUNNING".equals(r.get("status")))) {
        return runs;
      }
      Thread.sleep(100);
    }
    return fail("no " + expected + " terminal CI runs for " + repoId + " within the deadline");
  }

  private List<StubEventsServer.Put> awaitPuts(int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline && StubEventsServer.puts().size() < expected) {
      Thread.sleep(50);
    }
    Thread.sleep(300);
    return StubEventsServer.puts();
  }
}
