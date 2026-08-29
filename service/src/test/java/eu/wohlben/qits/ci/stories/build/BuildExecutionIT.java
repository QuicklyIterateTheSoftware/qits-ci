package eu.wohlben.qits.ci.stories.build;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.ci.stories.support.MockContainers;
import eu.wohlben.qits.ci.stories.support.StoryDaemon;
import eu.wohlben.qits.ci.stories.support.StoryGitHost;
import eu.wohlben.qits.ci.stories.support.StoryIdentities;
import eu.wohlben.qits.ci.stories.support.StoryOrigin;
import eu.wohlben.qits.ci.stories.support.StoryTarget;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import eu.wohlben.qits.cidaemon.protocol.RunStep;
import eu.wohlben.qits.cidaemon.protocol.Stream;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * <b>What actually runs a build step</b>, end to end, with nobody in it holding a docker socket.
 *
 * <p>The arrangement is the whole design of this service, and it is inside out on purpose:
 *
 * <ol>
 *   <li>qits-ci asks <b>qits-containers</b> — over plain HTTP, because this process holds no docker
 *       socket and spawns no process — to put a container somewhere. The spec's environment carries
 *       a per-container id and secret, minted for this one step.
 *   <li>The container's own {@code qits-ci-daemon} <b>dials out</b> to {@code ws://…/ci/daemon} with
 *       those two headers. qits-ci never dials in, which is why a step container needs no address.
 *   <li>The daemon says {@code Initialized} once its checkout is done, and <b>the step is the reply
 *       to that</b>. The host initiates nothing: a script leaves this process as one field of one
 *       JSON frame, and executes as the daemon's child inside the sandbox.
 *   <li>Output comes back as {@code StepChunk} frames while the step runs, and one {@code
 *       StepFinished} ends it.
 * </ol>
 *
 * <p><b>The daemon in this story is a real client of the real socket.</b> {@link StoryDaemon} is a
 * Vert.x WebSocket framing the vendored protocol exactly as the native binary does, and — the part
 * that makes this evidence rather than a fixture — it learns its credentials <b>only</b> from the
 * workload spec that reached {@link MockContainers}. Nothing here reads the host's launch table. An
 * admitted dial is therefore a measurement of the whole path: qits-ci minted a secret, put it in a
 * container spec, sent it to the orchestrator, and then recognised it coming back off a socket.
 *
 * <p>What is <b>not</b> proved here, and is out of reach in this container: a real image, a real
 * daemon binary and a real docker daemon. Those are {@code CiDaemonHandshakeIT} and {@code
 * CiDaemonGateIT}, which are tagged {@code extended} and need docker, a published step image, a
 * built daemon binary and a container route back to the JVM. What this story adds to them is that
 * it needs none of it — so the flow is documented on every ordinary build.
 *
 * <p><b>Two stories, and the second is deliberately not the first one's tail.</b> The peer that
 * asked for the build polls the run it asked for, with the machine role, which is what
 * qits-platform-maintenance does while it waits out a bump. A <b>person</b> reading the transcript
 * afterwards is a different door, a different identity track and — the claim the diagram makes —
 * a walk that reaches no other service at all: the step's output is a row by then, so reading it
 * costs the git host nothing and the orchestrator nothing.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("eu.wohlben.qits.ci.stories.support.StoryOrigin#gitPresent")
public class BuildExecutionIT {

  static final String BUILDS = "builds";
  static final String OPERATIONS = "operations";

  static final String STEP_SLUG = "a-build-step-connects-to-qits-ci-and-streams-its-output";
  static final String TRANSCRIPT_SLUG = "an-operator-reads-a-finished-build-s-transcript";

  /** The peer that asked for the build, and later polls the run it asked for. */
  static final String PLATFORM = "a platform service";

  /** The person reading afterwards, through the edge's forward-auth headers. */
  static final String OPERATOR = "an operator";

  /** A fixed readable id — see {@link BuildTriggerIT#REPO_ID} for why it is not a UUID. */
  static final String REPO_ID = "story-daemon-build";

  static final String TRIGGER_FILE = "ci-event-build-on-release.yml";

  static final String TRIGGER_PATH = StoryOrigin.CONFIG_DIR + "/" + TRIGGER_FILE;

  static final String EVENT = "SoftwareRelease";

  /** What this repository declared an interest in — unique to it, so no other story's event fires it. */
  static final String DEPENDENCY = "qits-service-mock";

  /** The step's image. Nothing pulls it: no container is ever created, only asked for. */
  static final String STEP_IMAGE = "alpine:3";

  /** The step's script, verbatim — and the host must hand back exactly these bytes on the wire. */
  static final String STEP_SCRIPT = "echo building against the new release\n";

  static final int STEP_TIMEOUT_SECONDS = 120;

  /** What the daemon reports the step printed, on each of the two streams. */
  static final String STDOUT_LINE = "building against the new release\n";

  static final String STDERR_LINE = "note: nothing to do\n";

  /** How long a launch may take to arrive: the run is queued behind whatever the worker is doing. */
  private static final Duration LAUNCH_PATIENCE = Duration.ofSeconds(60);

  private static String publishedSha;

  /** Handed from the first story to the second — the run whose transcript is read. */
  private static String runId;

  /**
   * The two credentials this story handles, kept so {@code @AfterAll} can assert neither reached the
   * bundle. The second one matters most: a per-container secret is what admits a dial to the control
   * socket, and a report is a document somebody publishes.
   */
  private static String platformBearer;

  private static String daemonSecret;

  private static String triggerFile() {
    return """
        event: %s
        when:
          - name: { exact: %s }
        steps:
          - image: %s
            timeout-seconds: %d
            script: |
              echo building against the new release
        """
        .formatted(EVENT, DEPENDENCY, STEP_IMAGE, STEP_TIMEOUT_SECONDS);
  }

  @BeforeAll
  static void tapEveryEndAndPublishTheRepository() throws Exception {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryGitHost.install();
    MockContainers.installSource();
    publishedSha = StoryOrigin.publish(REPO_ID, TRIGGER_FILE, triggerFile());
    // …and then wait for it to be a candidate. See StoryOrigin#awaitCandidateListing: qits-ci
    // caches the git host's repository listing, so a repository published inside that window is one
    // the engine has not heard of yet and an event that should match it matches nothing.
    StoryOrigin.awaitCandidateListing();
  }

  @UserStory(value = "A build step connects to qits-ci and streams its output", category = BUILDS)
  @UserStoryDescription(
      """
      A pipeline declares a step, and the step runs somewhere qits-ci cannot reach. qits-ci asks
      the platform's container service to put a container at a place, hands it an id and a secret
      nobody else holds, and then waits — because the container's daemon is what dials back. The
      step itself travels down that connection as the reply to the daemon saying its checkout is
      done, and the build's output comes back up it a frame at a time. This story is that whole
      exchange, played by a real client of the real socket that learns its credentials the way a
      container learns them: out of the container spec.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aBuildStepConnectsAndStreamsItsOutput(Interactions story) throws Exception {
    // --- somebody releases something this repository depends on --------------------------------
    NetworkCapture.actor(PLATFORM);
    platformBearer = StoryIdentities.platformToken();
    JsonPath answered =
        given()
            .header("Authorization", "Bearer " + platformBearer)
            .contentType(ContentType.JSON)
            .body(Map.of("name", EVENT, "payload", Map.of("name", DEPENDENCY, "version", "4.5.6")))
            .when()
            .post(StoryTarget.TRIGGER_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    List<String> runIds = answered.getList("runIds");
    assertEquals(1, runIds.size(), "the repository that declared the interest was accepted for a run");
    runId = runIds.getFirst();
    story.note("a release this repository depends on is announced, and a run is accepted").as("run-accepted");

    // --- qits-ci asks the orchestrator for a container, and that is where the secret is ---------
    MockContainers.Launch launch = MockContainers.awaitLaunch(LAUNCH_PATIENCE);
    String daemonId = launch.environment().get(StoryDaemon.ID_VARIABLE);
    String secret = launch.environment().get(StoryDaemon.SECRET_VARIABLE);
    daemonSecret = secret;
    // Read once, used twice: to dial with, and afterwards to prove it is nowhere in the bundle.
    assertNotNull(daemonId, "the spec must carry the per-container daemon id");
    assertNotNull(secret, "…and the secret that is the whole of this socket's authentication");
    // The address is a cross-repo contract: the daemon binary dials this string verbatim, so the
    // path in it and the @WebSocket literal on the endpoint are the same fact spelled twice.
    assertTrue(
        launch.environment().get(StoryDaemon.URL_VARIABLE).endsWith(StoryTarget.DAEMON_PATH),
        "the container is told to dial " + StoryTarget.DAEMON_PATH);
    story
        .note("qits-ci asked qits-containers for one container, carrying an id and a secret for it")
        .as("container-requested");

    // --- the container's daemon dials back ------------------------------------------------------
    NetworkCapture.actor(StoryDaemon.ACTOR);
    try (StoryDaemon daemon = StoryDaemon.dial(StoryTarget.daemonSocket(), daemonId, secret)) {
      daemon.hello(daemonId);
      assertEquals(
          CiDaemonProtocol.CAPABILITY_VERSION,
          daemon.awaitAck().capabilityVersion(),
          "the host answers with its own capability version, which a mismatched daemon exits on");
      daemon.heartbeat();
      story
          .note(
              "the step container's daemon dials out, asserts its own qits:system role at the"
                  + " handshake, and is admitted on the secret minted for this container")
          .as("daemon-admitted");

      // The checkout is the daemon's own business; the host learns of it as one frame, and answers
      // it with the step. Nothing before this point could have been cancelled — there is no step yet.
      daemon.initialized();
      RunStep step = daemon.awaitRunStep();
      assertEquals(STEP_SCRIPT, step.script(), "the script is the repository's own bytes, unchanged");
      assertEquals(
          STEP_TIMEOUT_SECONDS,
          step.timeoutSeconds(),
          "…with the deadline the pipeline declared, enforced inside the container");
      assertNotNull(step.correlationId(), "…correlated, so late frames from a past step are ignored");
      story
          .note("the daemon reports its checkout done, and the step arrives as the reply to that")
          .as("step-delivered");

      daemon.chunk(step.correlationId(), 0, Stream.OUT, STDOUT_LINE);
      daemon.chunk(step.correlationId(), 1, Stream.ERR, STDERR_LINE);
      daemon.finished(step.correlationId(), 0, false);
      story.note("the step's output streams back frame by frame, and then it ends").as("step-finished");
    }

    // --- the peer that asked for the build polls the run it asked for ---------------------------
    // The machine role, not a person's: a peer waiting out a build it triggered must not be handed
    // qits:admin to do it, which is why the read routes accept {qits:admin, qits:system}.
    NetworkCapture.actor(PLATFORM);
    Map<String, Object> run = awaitTerminalRun(runId, StoryIdentities.platformToken());
    assertEquals("SUCCESS", run.get("status"), "the exit code the daemon reported is the verdict");
    assertEquals(publishedSha, run.get("commitSha"));
    List<Map<String, Object>> steps = readSteps(run);
    assertEquals(1, steps.size(), "the pipeline declared one step and one step was recorded");
    assertEquals(0, steps.getFirst().get("exitCode"));
    assertEquals(STEP_IMAGE, steps.getFirst().get("image"));
    assertTrue(
        String.valueOf(steps.getFirst().get("output")).contains(STDOUT_LINE.strip()),
        "the step's row holds what came up the socket");
    story.note("the run is green, and the step's row holds what the daemon streamed").as("run-green");

    // The teardown is the last thing the run worker does, in a finally, and it is far-side traffic:
    // a DELETE that lands after this story returns is a DELETE in the NEXT story's diagram.
    MockContainers.awaitRemoved(launch.containerName(), Duration.ofSeconds(30));
    StoryGitHost.awaitRead(blobPath());
  }

  @UserStory(value = "An operator reads a finished build's transcript", category = OPERATIONS)
  @UserStoryDescription(
      """
      Afterwards, a person wants to know what happened. They open the run and read the step's
      transcript, then step back to the repository listing to see where CI has been. Two things
      are worth saying about that walk. It is authenticated as a PERSON — this service
      authenticates nobody itself, so the platform edge asserts who the caller is and what they
      may do — and it reaches nothing else at all: the build's output is a row by the time anyone
      reads it, so no git host and no container service is on the path.
      """)
  @Order(2)
  void anOperatorReadsTheTranscript(Interactions story) {
    NetworkCapture.actor(OPERATOR);
    assertNotNull(runId, "the story that recorded the run must have run first");

    Map<String, Object> run =
        StoryIdentities.operator(given())
            .when()
            .get(StoryTarget.runPath(runId))
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getMap("$");
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(EVENT, run.get("triggerEventName"), "the run says what caused it");
    assertEquals(TRIGGER_PATH, run.get("configPath"), "…and which file declared the pipeline");

    List<Map<String, Object>> steps = readSteps(run);
    String transcript = String.valueOf(steps.getFirst().get("output"));
    // Both streams, in the order they were written: the relay is one buffer with one budget, and
    // stderr is a step's output too — a transcript that dropped it would hide every warning.
    assertTrue(transcript.contains(STDOUT_LINE.strip()), "stdout is in the transcript");
    assertTrue(transcript.contains(STDERR_LINE.strip()), "…and so is stderr");
    assertNotNull(steps.getFirst().get("startedAt"), "the step is timestamped by the HOST");
    assertNotNull(steps.getFirst().get("finishedAt"), "…at both ends, never by the container");
    story.note("the operator reads the finished step, its exit code and its whole transcript").as("transcript-read");

    // …and steps back to what CI has been doing. `repositories` names ids this instance OBSERVED —
    // ci owns no repository — and `summary` is that listing plus the runs a client would otherwise
    // make one request per repository to find.
    List<String> repositoryIds =
        StoryIdentities.operator(given())
            .when()
            .get(StoryTarget.REPOSITORIES_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("repositoryIds");
    assertTrue(repositoryIds.contains(REPO_ID), "the repository this run was about is in the listing");

    List<Map<String, Object>> summaries =
        StoryIdentities.operator(given())
            .when()
            .get(StoryTarget.REPOSITORY_SUMMARY_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("repositories");
    Map<String, Object> summary =
        summaries.stream()
            .filter(entry -> REPO_ID.equals(entry.get("repositoryId")))
            .findFirst()
            .orElseGet(() -> fail("the repository summary must name " + REPO_ID));
    assertNotNull(summary.get("lastRun"), "…with the newest run on it, whole");
    story.note("and the repository listing shows the same run as the newest one there").as("listing-read");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    if (!StoryOrigin.gitPresent()) {
      return;
    }

    // --- the step, and the four planes one step touches ----------------------------------------
    ReportAssertions.assertComplete(BUILDS, STEP_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(BUILDS, STEP_SLUG, "run-accepted");
    ReportAssertions.assertStepId(BUILDS, STEP_SLUG, "container-requested");
    ReportAssertions.assertStepId(BUILDS, STEP_SLUG, "daemon-admitted");
    ReportAssertions.assertStepId(BUILDS, STEP_SLUG, "step-delivered");
    ReportAssertions.assertStepId(BUILDS, STEP_SLUG, "step-finished");
    ReportAssertions.assertStepId(BUILDS, STEP_SLUG, "run-green");

    // (1) the event that started it, and the poll that waited it out — both the same machine peer.
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.TRIGGER_PATH + " -> 200");
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.RUNS_PATH + "/{id} -> 200");
    // (2) the pipeline, read out of the git host at the commit the listing resolved to.
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", blobPath(), 200));
    // (3) the container: asked for, and taken away again. qits-ci holds no docker socket, so these
    // two calls are the entirety of its container vocabulary for one step.
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        MockContainers.SERVICE_NAME,
        MockContainers.label("PUT", "", 200));
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        MockContainers.SERVICE_NAME,
        MockContainers.label("DELETE", "volumes=false&logs=false", 200));
    // (4) the socket, and the frames on it. The DIAL is the container's: qits-ci never dials in.
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.SOCKET,
        StoryDaemon.ACTOR,
        StoryTarget.SERVICE,
        "CONNECT " + StoryTarget.DAEMON_PATH);
    ReportAssertions.assertEdge(
        BUILDS, STEP_SLUG, NetworkEdge.EVENT, StoryDaemon.ACTOR, StoryTarget.SERVICE, "hello");
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.EVENT,
        StoryTarget.SERVICE,
        StoryDaemon.ACTOR,
        "ack capabilityVersion " + CiDaemonProtocol.CAPABILITY_VERSION);
    ReportAssertions.assertEdge(
        BUILDS, STEP_SLUG, NetworkEdge.EVENT, StoryDaemon.ACTOR, StoryTarget.SERVICE, "initialized");
    // The one arrow INTO the container, and it exists only because the container asked for work.
    ReportAssertions.assertEdge(
        BUILDS, STEP_SLUG, NetworkEdge.EVENT, StoryTarget.SERVICE, StoryDaemon.ACTOR, "runStep");
    ReportAssertions.assertEdge(
        BUILDS, STEP_SLUG, NetworkEdge.EVENT, StoryDaemon.ACTOR, StoryTarget.SERVICE, "stepChunk OUT");
    ReportAssertions.assertEdge(
        BUILDS, STEP_SLUG, NetworkEdge.EVENT, StoryDaemon.ACTOR, StoryTarget.SERVICE, "stepChunk ERR");
    ReportAssertions.assertEdge(
        BUILDS,
        STEP_SLUG,
        NetworkEdge.EVENT,
        StoryDaemon.ACTOR,
        StoryTarget.SERVICE,
        "stepFinished exit 0");
    // Three initiators and no fourth: the peer that asked, the container that dialled back, and
    // qits-ci itself. Nothing else reached this service and this service reached nothing else.
    ReportAssertions.assertEdge(
        BUILDS, STEP_SLUG, NetworkEdge.EVENT, StoryDaemon.ACTOR, StoryTarget.SERVICE, "heartbeat");
    ReportAssertions.assertOnlyEdgesFrom(
        BUILDS, STEP_SLUG, List.of(PLATFORM, StoryDaemon.ACTOR, StoryTarget.SERVICE));
    // Neither credential is in the bundle: not the bearer that opened the trigger, and not the
    // per-container secret that admitted the socket.
    ReportAssertions.assertNotLeaked(BUILDS, STEP_SLUG, platformBearer);
    ReportAssertions.assertNotLeaked(BUILDS, STEP_SLUG, daemonSecret);

    // --- the transcript ------------------------------------------------------------------------
    ReportAssertions.assertComplete(OPERATIONS, TRANSCRIPT_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(OPERATIONS, TRANSCRIPT_SLUG, "transcript-read");
    ReportAssertions.assertStepId(OPERATIONS, TRANSCRIPT_SLUG, "listing-read");
    ReportAssertions.assertEdge(
        OPERATIONS,
        TRANSCRIPT_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.RUNS_PATH + "/{id} -> 200");
    ReportAssertions.assertEdge(
        OPERATIONS,
        TRANSCRIPT_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.REPOSITORIES_PATH + " -> 200");
    ReportAssertions.assertEdge(
        OPERATIONS,
        TRANSCRIPT_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.REPOSITORY_SUMMARY_PATH + " -> 200");
    // Exactly three arrows, all of them one person's: reading a finished build reaches nobody.
    ReportAssertions.assertEdgeCount(OPERATIONS, TRANSCRIPT_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(OPERATIONS, TRANSCRIPT_SLUG, List.of(OPERATOR));
    ReportAssertions.assertNoEdgesTo(OPERATIONS, TRANSCRIPT_SLUG, StoryGitHost.SERVICE_NAME);
    ReportAssertions.assertNoEdgesTo(OPERATIONS, TRANSCRIPT_SLUG, MockContainers.SERVICE_NAME);
  }

  private static String blobPath() {
    return "/git/" + REPO_ID + "/blob/" + publishedSha + "/" + TRIGGER_PATH;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> readSteps(Map<String, Object> run) {
    List<Map<String, Object>> steps = (List<Map<String, Object>>) run.get("steps");
    assertNotNull(steps, "a run read by id carries its steps");
    return steps;
  }

  /** Poll one run until it has left {@code QUEUED}/{@code RUNNING}, as the peer that asked for it. */
  private static Map<String, Object> awaitTerminalRun(String id, String token) {
    long deadline = System.currentTimeMillis() + 120_000;
    while (System.currentTimeMillis() < deadline) {
      Map<String, Object> run =
          given()
              .header("Authorization", "Bearer " + token)
              .when()
              .get(StoryTarget.runPath(id))
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getMap("$");
      Object status = run.get("status");
      if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
        return run;
      }
      sleep();
    }
    return fail("run " + id + " never reached a terminal status");
  }

  private static void sleep() {
    try {
      Thread.sleep(200);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
