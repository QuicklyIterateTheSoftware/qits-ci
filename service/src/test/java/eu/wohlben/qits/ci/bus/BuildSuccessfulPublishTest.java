package eu.wohlben.qits.ci.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.FakeCiStepRunner;
import eu.wohlben.qits.ci.githost.FakeGitHostRepoListing;
import eu.wohlben.qits.ci.projects.FakeProjectsRepoListing;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
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
 * The publish hook, end to end inside one JVM: a domain event matches a repository's trigger file,
 * the run goes green, and a {@code BuildSuccessful} lands on qits-events as one idempotent PUT.
 *
 * <p>It drove the run through a push until 2026-09-05 — a {@code SCMPublishCommit} handed to the
 * retired {@code ScmPublishCommitListener}, which was the shortest path to a green run. An ordinary
 * push triggers nothing now, so the shortest path is the trigger type that is left; nothing about
 * what is asserted below moved with it, because the publish hook hangs off a run finishing green and
 * never off what caused the run.'
 *
 * <p><b>This is the only test in the repo that runs with the event bus switched on</b>, which is
 * half of what it is for. `%test.qits.eventstream.enabled=false` in the shipped
 * application.properties is the posture every other suite inherits — no dials, no outbox, no stream
 * — and the profile below turns it back on for this class alone, against {@link StubEventsServer}
 * rather than anything real. A test that needed a running qits-events would break the clone-alone
 * rule; one that asserted "no exception was thrown" would pass against a hook that publishes
 * nothing.
 *
 * <p>What is asserted is the contract the other side was built against, not this side's internals:
 * one PUT per green run, at a v4 UUID of the publisher's choosing, carrying the envelope's {@code
 * name} as the signature and the run's own coordinates in the canonical payload. The three-way PUT
 * semantics, the outbox and the retry schedule belong to the eventstream suite; the round trip
 * through a real qits-events belongs to the platform.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
@TestProfile(BuildSuccessfulPublishTest.EventstreamOn.class)
@WithTestResource(StubEventsServer.class)
public class BuildSuccessfulPublishTest {

  private static final String CONFIG_ONE_STEP =
      """
      steps:
        - image: alpine:3
          script: echo ok
      """;

  /** Undoes the shipped `%test` darkness for this class only. */
  public static class EventstreamOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.eventstream.enabled", "true");
    }
  }

  private final ObjectMapper json = new ObjectMapper();

  /** This class's own event name, so no other suite's trigger file can be fired by one of these. */
  private static final String EVENT_NAME = "CiPublishProbe";

  private static final String TRIGGER_PATH = ".config/qits/ci-event-publish.yml";

  @Inject FakeCiStepRunner fakeRunner;

  @Inject FakeGitHostRepoListing gitHostListing;

  /** The platform catalogue, which is the only listing that can answer a public NAME. */
  @Inject FakeProjectsRepoListing projectsListing;

  @BeforeEach
  void resetState() {
    fakeRunner.reset();
    StubEventsServer.reset();
    gitHostListing.set();
    projectsListing.unset();
  }

  @Test
  public void aGreenRunPublishesOneBuildSuccessfulCarryingTheRunsCoordinates() throws Exception {
    String repoId = seedOriginWithConfig(CONFIG_ONE_STEP);
    String sha = tipOf(repoId);
    fire(repoId);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));

    List<StubEventsServer.Put> puts = awaitPuts(1);
    assertEquals(1, puts.size(), "one green run is one publish");

    // The id is the publisher's, in the path — which is what makes a retry a replay rather than a
    // duplicate. A v4 UUID is what the contract fixes.
    UUID eventId = UUID.fromString(puts.get(0).id());
    assertEquals(4, eventId.version());

    JsonNode envelope = json.readTree(puts.get(0).body());
    assertEquals("BuildSuccessful", envelope.get("name").asText(), "name doubles as the signature");
    // occurredAt is mandatory on the wire and is the run's own finish, not the publish moment.
    Instant occurredAt = Instant.parse(envelope.get("occurredAt").asText());
    assertTrue(envelope.get("description").isNull(), "a published event has no human account");

    // The payload is a canonical JSON *string* the server stores verbatim, never a nested object.
    assertTrue(envelope.get("payload").isTextual());
    JsonNode payload = json.readTree(envelope.get("payload").asText());
    assertEquals(run.get("id"), payload.get("runId").asText());
    assertEquals(repoId, payload.get("repoId").asText());
    assertEquals("main", payload.get("branch").asText());
    assertEquals(sha, payload.get("commitSha").asText());
    assertEquals(occurredAt, Instant.parse(payload.get("finishedAt").asText()));

    // qits-ci has no image digest to report at the SUCCESS transition (a step publishes from inside
    // its own container and answers with an exit code), and an absent field is omitted from the
    // canonical form rather than written as an explicit null.
    assertFalse(payload.has("imageDigest"), payload.toString());
    // Identity travels in the envelope's id, never in the payload.
    assertFalse(payload.has("eventId"), payload.toString());
    // This candidate came off a listing that answers ids only, so the pair is omitted rather than
    // nulled — the run addresses its image by the storage id, exactly as before names existed.
    assertFalse(payload.has("projectId"), payload.toString());
    assertFalse(payload.has("repoName"), payload.toString());
  }

  @Test
  public void aNameAddressedRunCarriesTheProjectAndRepoNameOnTheWire() throws Exception {
    String repoId = seedOriginWithConfig(CONFIG_ONE_STEP);
    // A name-addressed candidate: the git host serves the same bare under /git/<projectId>/<repoName>
    // and qits-projects' listing is what supplies the pair.
    StubGitHost.alias("acme", "widget", repoId);
    projectsListing.set(new CiRepoRef(repoId, "acme", "widget"));
    fire(repoId);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));

    List<StubEventsServer.Put> puts = awaitPuts(1);
    JsonNode envelope = json.readTree(puts.get(0).body());
    JsonNode payload = json.readTree(envelope.get("payload").asText());

    // The pair rides the wire, so the deployer names the image qits/<repoName>:<sha> rather than
    // falling back to the storage id.
    assertEquals("acme", payload.get("projectId").asText());
    assertEquals("widget", payload.get("repoName").asText());
    assertEquals(repoId, payload.get("repoId").asText());
  }

  @Test
  public void eachRunGetsItsOwnEventId() throws Exception {
    String first = seedOriginWithConfig(CONFIG_ONE_STEP);
    fire(first);
    awaitTerminalRun(first);

    String second = seedOriginWithConfig(CONFIG_ONE_STEP);
    fire(second);
    awaitTerminalRun(second);

    List<StubEventsServer.Put> puts = awaitPuts(2);
    assertEquals(2, puts.size(), "two green runs are two publishes");
    assertNotEquals(
        puts.get(0).id(),
        puts.get(1).id(),
        "a reused id is a 400 from qits-events, not a second event");
  }

  // --- the event, as the trigger engine is given one ---

  /** Fires this class's event for one repository, through the same door an operator would use. */
  private void fire(String repoId) {
    io.restassured.RestAssured.given()
        .contentType(io.restassured.http.ContentType.JSON)
        .body(
            """
            {"name":"%s","occurredAt":"2026-09-05T09:00:00Z","payload":{"publishRepo":"%s"}}"""
                .formatted(EVENT_NAME, repoId))
        .when()
        .post("/ci/api/events/trigger")
        .then()
        .statusCode(200);
  }

  // --- git plumbing (StubGitHost serves these bares as <base>/git/<repoId>) ---

  /**
   * Seeds a bare origin at {@code <git-host>/git/<repoId>} whose {@code main} already carries the
   * pipeline config — the shortest path to a run, since nothing here asserts anything about the
   * push itself. {@code CiPipelineBoundaryTest} is where clone-commit-push is the subject.
   */
  private String seedOriginWithConfig(String config) throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path seed = Files.createTempDirectory("ci-bus-seed");
    git(seed, "init", "-q", "-b", "main");
    Path configFile = seed.resolve(TRIGGER_PATH);
    Files.createDirectories(configFile.getParent());
    // The selection names this repository and nothing else: every repository this JVM has seeded is
    // a candidate for every event evaluated in it, so a shared literal would let one method's event
    // fire another's repository and "one green run is one publish" would stop being about this test.
    Files.writeString(
        configFile,
        """
        event: %s
        when:
          - publishRepo: { exact: %s }
        %s"""
            .formatted(EVENT_NAME, repoId, config));
    git(seed, "add", ".");
    git(seed, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", "ci config");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    gitHostListing.set(repoId);
    return repoId;
  }

  private String tipOf(String repoId) throws Exception {
    return git(gitHostRoot().resolve(repoId), "rev-parse", "main").trim();
  }

  /** The directory this suite's {@code qits.ci.git-host-url} points at. */
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

  // --- waiting ---

  /** Deadline-polls the run list until the (single) run reaches a terminal status. */
  private Map<String, Object> awaitTerminalRun(String repoId) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs =
          given()
              .when()
              .get("/ci/api/runs?repositoryId=" + repoId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("runs");
      if (runs.size() == 1 && !"RUNNING".equals(runs.get(0).get("status"))) {
        return runs.get(0);
      }
      Thread.sleep(100);
    }
    return fail("no terminal CI run for " + repoId + " within the deadline");
  }

  /**
   * Waits for at least {@code expected} PUTs and then a moment longer, so "exactly one" is an
   * assertion about the hook rather than about how fast this thread got here — the publish happens
   * on the run worker, after the terminal row the poll above sees is already committed.
   */
  private List<StubEventsServer.Put> awaitPuts(int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 10_000;
    while (System.currentTimeMillis() < deadline && StubEventsServer.puts().size() < expected) {
      Thread.sleep(50);
    }
    Thread.sleep(300);
    return StubEventsServer.puts();
  }
}
