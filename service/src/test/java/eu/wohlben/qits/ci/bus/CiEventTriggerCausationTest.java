package eu.wohlben.qits.ci.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.eventstream.control.EventDispatcher;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.test.common.TestResourceScope;
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
 * <p><b>Both trigger types draw that edge now, and the push is the half that used to be missing.</b>
 * A push run published a <b>root</b> event while the intake was an HTTP POST with no event behind it
 * — so a chain that ran release → push → build → deploy broke in the middle and nothing downstream
 * could be traced past the build. A push is an {@code SCMPublishCommit} now, its id lands on the run
 * row like any trigger's, and the assertion below is the same one, made twice on one run history.
 *
 * <p><b>The release fan-out is the same claim in its plural form</b>, which is why it lives here
 * rather than beside the seam test: N {@code SoftwareRelease} events under one parent proves that
 * the stamp is a non-consuming read rather than something the first publish spends. The bytes of
 * those payloads are asserted whole, because they are the contract every downstream release pipeline
 * reads.
 *
 * <p>Frames are handed to {@link EventDispatcher} directly rather than broadcast by the stub, the
 * same choice the eventstream suite makes: a routing claim is about dispatch, and only a claim
 * about the wire needs a socket. The one wire claim here — that the subscribe frame is {@code ["*"]}
 * — reads what the stub recorded when the application dialled it at startup.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
@TestProfile(BuildSuccessfulPublishTest.EventstreamOn.class)
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

  @Inject FakeCiStepRunner fakeRunner;

  @Inject ScmPublishCommitListener pushes;

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
    // candidate source means by a candidate — and it is the push half of the causation assertion.
    String pushEventId = announcePush(repoId, tipOf(repoId));
    Map<String, Object> pushed = awaitRuns(repoId, 1).get(0);
    assertEquals("POST_RECEIVE", pushed.get("triggerType"));
    assertEquals(".config/qits/ci-post-receive.yml", pushed.get("configPath"));
    assertEquals(
        pushEventId,
        pushed.get("triggerEventId"),
        "the SCMPublishCommit that announced the push is what caused this run");

    List<StubEventsServer.Put> afterPush = awaitPuts(1);
    JsonNode pushEnvelope = json.readTree(afterPush.get(0).body());
    assertEquals(
        pushEventId,
        pushEnvelope.get("parentId").asText(),
        "a push run's BuildSuccessful is caused by the push event, which is what keeps"
            + " release → push → build → deploy one chain");

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
    announcePush(repoId, tipOf(repoId));
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
    announcePush(repoId, tipOf(repoId));
    awaitRuns(repoId, 1);

    // A real BuildSuccessful, from a repository nothing declares a selection for.
    dispatcher.dispatch(frame(UUID.randomUUID().toString(), "a-repo-nobody-listens-for"));
    Thread.sleep(1_500);
    assertEquals(1, runsOf(repoId).size());
  }

  @Test
  public void aReleasePipelineFansOutOneSoftwareReleasePerDeclaredArtifact() throws Exception {
    String released = upstream();
    String repoId = seedOriginWith(releaseTrigger(released));
    announcePush(repoId, tipOf(repoId));
    awaitRuns(repoId, 1);
    awaitPuts(1);

    String eventId = UUID.randomUUID().toString();
    dispatcher.dispatch(scmReleaseFrame(eventId, released));
    awaitRuns(repoId, 2);

    // One green run, four events in total: the push's BuildSuccessful, the triggered run's own
    // BuildSuccessful — unchanged, every green run still announces itself — and then one
    // SoftwareRelease per declaration.
    List<StubEventsServer.Put> puts = awaitPuts(4);
    assertEquals(4, puts.size(), "a build announcement plus two artifacts");
    assertEquals("BuildSuccessful", json.readTree(puts.get(1).body()).get("name").asText());

    JsonNode npm = json.readTree(puts.get(2).body());
    assertEquals("SoftwareRelease", npm.get("name").asText());
    assertEquals(eventId, npm.get("parentId").asText(), "N siblings under one parent");
    // repoId is the same string `repository` carries, under the name the platform addresses a
    // repository by; projectId is simply ABSENT, because the stub git host's listing answers ids
    // alone and NON_NULL inclusion writes no key for what qits-ci does not know. That absence is the
    // shipped behaviour on an id-addressed platform and is asserted here rather than assumed.
    assertEquals(
        "{\"packageName\":\"@qits/ui-components\",\"packageType\":\"npm\",\"repoId\":\""
            + repoId
            + "\",\"repository\":\""
            + repoId
            + "\",\"version\":\"1.4.0\"}",
        npm.get("payload").asText());

    JsonNode image = json.readTree(puts.get(3).body());
    assertEquals("SoftwareRelease", image.get("name").asText());
    assertEquals(eventId, image.get("parentId").asText());
    assertEquals(
        "{\"packageName\":\"qits/qits-stt\",\"packageType\":\"docker\",\"repoId\":\""
            + repoId
            + "\",\"repository\":\""
            + repoId
            + "\",\"version\":\"1.4.0\"}",
        image.get("payload").asText());

    // Every event is its own occurrence: the PUT path is the idempotency key, and two artifacts that
    // shared one would be a 400 on the second.
    assertNotEquals(puts.get(2).id(), puts.get(3).id());
  }

  // --- the frame, as the socket would deliver it ---

  /** A release pipeline: it selects its own SCM release and declares the two artifacts it ships. */
  private static String releaseTrigger(String released) {
    return """
        event: SCMRelease
        when:
          - repository: { exact: %s }
        artifacts:
          - { type: npm, name: "@qits/ui-components" }
          - { type: docker, name: qits/qits-stt }
        steps:
          - image: alpine:3
            script: ./publish-tag.sh
        """
        .formatted(released);
  }

  /**
   * The release event as <b>qits-projects</b> publishes it: same signature, same payload fields, and
   * a {@code branch} that names the release request's backing branch rather than a branch anybody
   * pushed. That branch is deleted when the tag is created, so it is already gone when this frame is
   * dispatched — which is exactly why it is spelled that way here. Nothing on this path reads it:
   * the trigger matches on {@code repository}, the run builds {@code main}, and the announcement's
   * coordinates come from {@code version}.
   */
  private String scmReleaseFrame(String eventId, String released) throws Exception {
    String payload =
        "{\"branch\":\"release/9f2c1a7e-4b31-4c8e-9a11-6d0f5c2e8b44\",\"projectId\":\"p-1\","
            + "\"repository\":\""
            + released
            + "\",\"version\":\"1.4.0\"}";
    return "{\"id\":\""
        + eventId
        + "\",\"name\":\"SCMRelease\",\"occurredAt\":\"2026-08-01T09:00:00Z\",\"payload\":"
        + json.writeValueAsString(payload)
        + ",\"description\":null,\"parentId\":null}";
  }

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

  /** @return the announcing event's id, which is what the run it records is caused by */
  private String announcePush(String repoId, String newSha) {
    EventFrame frame = ScmPushFrames.push(repoId, "main", ZERO_SHA, newSha);
    pushes.onFrame(frame);
    return frame.id();
  }

  /** A bare origin carrying both trigger types, so one repository exercises both paths. */
  private String seedOrigin(String upstream) throws Exception {
    return seedOriginWith(trigger(upstream));
  }

  private String seedOriginWith(String triggerContent) throws Exception {
    String repoId = "trg-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Path seed = Files.createTempDirectory("ci-trigger-seed");
    git(seed, "init", "-q", "-b", "main");
    write(seed, ".config/qits/ci-post-receive.yml", POST_RECEIVE);
    write(seed, TRIGGER_PATH, triggerContent);
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
      if (runs.size() >= expected
          && runs.stream()
              .noneMatch(
                  r ->
                      "QUEUED".equals(r.get("status"))
                          || "RUNNING".equals(r.get("status")))) {
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
