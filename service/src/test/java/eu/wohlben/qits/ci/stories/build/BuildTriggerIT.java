package eu.wohlben.qits.ci.stories.build;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.api.TokenValidationBootstrapIT;
import eu.wohlben.qits.ci.stories.support.MockContainers;
import eu.wohlben.qits.ci.stories.support.StoryGitHost;
import eu.wohlben.qits.ci.stories.support.StoryIdentities;
import eu.wohlben.qits.ci.stories.support.StoryOrigin;
import eu.wohlben.qits.ci.stories.support.StoryTarget;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * <b>How a build starts.</b> Not a push — a push is an {@code SCMPublishCommit} off the event log
 * and there is no HTTP door to it — but the other trigger, which is the one this platform's release
 * train is built out of: a repository commits {@code .config/qits/ci-event-*.yml} naming a domain
 * event and a selection over its payload, and an arriving event runs that file's pipeline.
 *
 * <p>The event arrives through {@code POST /ci/api/events/trigger}, the second inbound adapter of
 * the same engine — a hand-supplied event, built into the same {@code Arrival} the bus builds, so
 * the engine cannot tell the two apart. It is the door a bootstrap replay knocks on, and it is the
 * only one a story can knock on without standing up a whole qits-events beside a launched artifact.
 *
 * <p><b>Two stories, and the second is what the first cannot say.</b> One event fires a pipeline;
 * one event that no repository selected fires nothing — and "fires nothing" is a claim about
 * absence that only the negative network assertions can make. {@code assertNoEdgesTo(qits-containers)}
 * is the interesting one: no repository matched, so no step was ever asked for, so nothing on this
 * platform started a container. A presence check cannot say that.
 *
 * <p>Both pipelines declare <b>no steps</b>. That is the smallest configuration that still records a
 * run — a commit declaring nothing is discarded, which is what opt-in means — and it takes the whole
 * path through the git host, SnakeYAML, the queue and Panache with no container involved. What a
 * step costs, and what a step container's daemon does with it, is {@link BuildExecutionIT}.
 *
 * <p>Everything here is <b>observed</b>: the framework's RestAssured tap draws what a story sent
 * into qits-ci, {@link StoryGitHost} draws what qits-ci read back out of the git host, and {@link
 * MockContainers} would draw a step container if one were asked for. A story method asserts and
 * notes; it draws nothing.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("eu.wohlben.qits.ci.stories.support.StoryOrigin#gitPresent")
public class BuildTriggerIT {

  static final String CATEGORY = "builds";
  static final String TRIGGERED_SLUG = "a-release-event-triggers-a-build";
  static final String UNSELECTED_SLUG = "an-event-nobody-declared-an-interest-in-builds-nothing";

  /** The two initiators. A machine writes the trigger; a person reads what it caused. */
  static final String PLATFORM = "a platform service";

  static final String OPERATOR = "an operator";

  /**
   * The repository whose pipeline this event selects. A fixed readable id, not a UUID: the default
   * label scrubber rewrites a whole UUID path segment to {@code {id}}, so a generated one would make
   * every story's git-host label read {@code GET /git/{id}/tree/main} and say nothing about which
   * repository ci went to. {@link StoryOrigin} deletes and recreates it, so fixed still means known.
   */
  static final String REPO_ID = "story-event-build";

  /** The trigger file's name. The {@code ci-event-} prefix is what makes it a repository's own. */
  static final String TRIGGER_FILE = "ci-event-release-train.yml";

  static final String TRIGGER_PATH = StoryOrigin.CONFIG_DIR + "/" + TRIGGER_FILE;

  /**
   * The event, and it is a real one: qits-ci announces {@code SoftwareRelease} once per artifact a
   * green release pipeline declared, and a repository that depends on that artifact declares an
   * interest in it. That is the release train, and this story is one hop of it.
   */
  static final String EVENT = "SoftwareRelease";

  /** What this repository declared an interest in — the selection, and it is unique to it. */
  static final String DEPENDENCY = "qits-userflows";

  /** …and what nobody declared an interest in. Same event, same shape, no pipeline. */
  static final String UNRELATED_DEPENDENCY = "a-library-nobody-depends-on";

  private static String publishedSha;

  /**
   * The bearer the first story presented, kept so {@code @AfterAll} can assert it is <b>not</b> in
   * the published bundle. A story that authenticates has a credential in its hands, and a report is
   * a document somebody publishes: the claim worth making is that the diagram carries the door and
   * the status, and never the key.
   */
  private static String platformBearer;

  /**
   * The trigger file this repository commits. {@code steps: []} is deliberate — see the class
   * javadoc — and the selection names the dependency rather than the repository, because that is
   * what a release train is: the event says what was released, the repository says what it cares
   * about.
   */
  private static String triggerFile() {
    return """
        event: %s
        when:
          - name: { exact: %s }
        steps: []
        """
        .formatted(EVENT, DEPENDENCY);
  }

  /**
   * Both taps and one fixture, once, before either story runs.
   *
   * <p>The RestAssured tap is the framework's and is idempotent per service, so installing it here
   * as well as in {@code TokenValidationBootstrapIT} draws nothing twice. {@link
   * StoryGitHost#install()} takes its floor here, which is what keeps the {@code @QuarkusTest}
   * suites' reads — the same stub serves them, in an earlier JVM, into the same file — out of every
   * diagram in this class.
   */
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

  @UserStory(value = "A release event triggers a build", category = CATEGORY)
  @UserStoryDescription(
      """
      The release train's first hop. A library is released somewhere on the platform and says so
      as a SoftwareRelease event; a repository that committed a trigger file naming that library
      is built because of it. Nobody configured qits-ci to know about either — the interest is a
      file in the repository, read out of the git host at the moment the event arrives, and the
      run that comes back names the event that caused it and the commit it was decided at.
      """)
  @UserflowRunsAfter(TokenValidationBootstrapIT.class)
  @Order(1)
  void aReleaseEventTriggersABuild(Interactions story) {
    platformBearer = StoryIdentities.platformToken();

    // The actor is set BEFORE the call: the tap sees a request, never a narrative role.
    NetworkCapture.actor(PLATFORM);
    story.note("a library on the platform is released, and the release is announced as an event");
    JsonPath answered =
        given()
            .header("Authorization", "Bearer " + platformBearer)
            .contentType(ContentType.JSON)
            .body(Map.of("name", EVENT, "payload", Map.of("name", DEPENDENCY, "version", "1.2.3")))
            .when()
            .post(StoryTarget.TRIGGER_PATH)
            .then()
            // 200 rather than 202 is the endpoint's guarantee: it EVALUATED before it answered.
            // A hand-supplied event rides no bus, holds no claim and will never be offered again,
            // so "queued" and "lost" would have been the same answer for it.
            .statusCode(200)
            .extract()
            .jsonPath();

    List<String> runIds = answered.getList("runIds");
    assertEquals(1, runIds.size(), "exactly the one repository that declared the interest built");
    assertTrue(
        answered.getInt("repositoriesRead") >= 1,
        "the evaluation must have read at least the repository it matched");
    assertTrue(
        answered.getList("repositoriesSkipped").isEmpty(),
        "no candidate was left unread, so an empty match would have meant 'nobody declared it'");
    story
        .note("qits-ci read every candidate repository's .config/qits/ and one of them selected it")
        .as("event-evaluated");

    // End (a), the run: it exists, it is about the commit main held, and it carries the event that
    // caused it. A run that named no cause would leave the train's chain broken at its first hop.
    NetworkCapture.actor(OPERATOR);
    Map<String, Object> run = awaitTerminalRun(REPO_ID);
    assertEquals(runIds.getFirst(), run.get("id"), "the id the trigger answered with is the row");
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(publishedSha, run.get("commitSha"), "…built at the commit main held");
    assertEquals(StoryOrigin.BRANCH, run.get("branch"));
    assertEquals(EVENT, run.get("triggerEventName"));
    assertEquals(
        answered.getString("eventId"), run.get("triggerEventId"), "…and names the event exactly");
    assertEquals(TRIGGER_PATH, run.get("configPath"), "…and which file declared the interest");
    story
        .note("the run exists, at the commit main held, naming the event that caused it")
        .as("run-recorded");

    // End (b), the git host: the interest was READ, not remembered. Both reads are in the diagram.
    StoryGitHost.awaitRead(blobPath());
  }

  @UserStory(value = "An event nobody declared an interest in builds nothing", category = CATEGORY)
  @UserStoryDescription(
      """
      The same door, the same event name, a payload naming a library no repository committed a
      trigger for. qits-ci still asks every repository it knows — which is the expensive half and
      the half that must not be skipped — and then records nothing at all: no run, and no step
      container asked of the orchestrator. An empty answer with nothing skipped is the engine
      saying "I asked everybody and none of them selected this", which is a different sentence
      from "I could not ask", and the API says which by answering 200 rather than 503.
      """)
  @Order(2)
  void anEventNobodySelectedBuildsNothing(Interactions story) {
    NetworkCapture.actor(PLATFORM);
    JsonPath answered =
        given()
            .header("Authorization", "Bearer " + StoryIdentities.platformToken())
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "name",
                    EVENT,
                    "payload",
                    Map.of("name", UNRELATED_DEPENDENCY, "version", "1.0.0")))
            .when()
            .post(StoryTarget.TRIGGER_PATH)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertTrue(answered.getList("runIds").isEmpty(), "no repository declared this dependency");
    assertTrue(
        answered.getList("repositoriesSkipped").isEmpty(),
        "…and every candidate was read, so the empty answer is a verdict rather than an outage");
    assertTrue(
        answered.getInt("repositoriesRead") >= 1,
        "the repository that declares a DIFFERENT interest was still asked");
    story
        .note("every candidate repository was read, and none of them selected this release")
        .as("nothing-selected");

    // The repository from the first story is still there with exactly its one run: an event nobody
    // selected must not add a row to somebody else's history either.
    NetworkCapture.actor(OPERATOR);
    List<Map<String, Object>> runs = runsFor(REPO_ID);
    assertEquals(1, runs.size(), "the neighbouring repository's history is untouched");
    story.note("and no run was recorded anywhere").as("nothing-recorded");

    StoryGitHost.awaitRead(triggerDirPath());
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    if (!StoryOrigin.gitPresent()) {
      return;
    }

    // --- the triggered build -----------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, TRIGGERED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, TRIGGERED_SLUG, "event-evaluated");
    ReportAssertions.assertStepId(CATEGORY, TRIGGERED_SLUG, "run-recorded");
    ReportAssertions.assertEdge(
        CATEGORY,
        TRIGGERED_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.TRIGGER_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        TRIGGERED_SLUG,
        NetworkEdge.HTTP,
        OPERATOR,
        StoryTarget.SERVICE,
        "GET " + StoryTarget.RUNS_PATH + " -> 200");
    // The two reads that ARE the trigger engine: list the config directory at the branch, then read
    // each declaring file at the sha that listing resolved to. One commit, whatever lands meanwhile.
    ReportAssertions.assertEdge(
        CATEGORY,
        TRIGGERED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", triggerDirPath(), 200));
    ReportAssertions.assertEdge(
        CATEGORY,
        TRIGGERED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", blobPath(), 200));
    // The actor set is the story's promise: a machine, a person, and the service's own read of the
    // git host. The request COUNTS behind them are the clients' — a poll is as many GETs as it took.
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, TRIGGERED_SLUG, List.of(PLATFORM, OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(CATEGORY, TRIGGERED_SLUG, MockContainers.SERVICE_NAME);
    ReportAssertions.assertNotLeaked(CATEGORY, TRIGGERED_SLUG, platformBearer);

    // --- the event nobody selected -------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY, UNSELECTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, UNSELECTED_SLUG, "nothing-selected");
    ReportAssertions.assertStepId(CATEGORY, UNSELECTED_SLUG, "nothing-recorded");
    ReportAssertions.assertEdge(
        CATEGORY,
        UNSELECTED_SLUG,
        NetworkEdge.HTTP,
        PLATFORM,
        StoryTarget.SERVICE,
        "POST " + StoryTarget.TRIGGER_PATH + " -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        UNSELECTED_SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryGitHost.SERVICE_NAME,
        StoryGitHost.label("GET", triggerDirPath(), 200));
    // The whole point of the story, and only a negative claim can make it: the evaluation cost a
    // read of every repository and NOT ONE container. Nothing reached the orchestrator.
    ReportAssertions.assertNoEdgesTo(CATEGORY, UNSELECTED_SLUG, MockContainers.SERVICE_NAME);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY, UNSELECTED_SLUG, List.of(PLATFORM, OPERATOR, StoryTarget.SERVICE));
  }

  /** {@code /git/story-event-build/tree/main/.config/qits} — the listing the engine starts from. */
  private static String triggerDirPath() {
    return "/git/" + REPO_ID + "/tree/" + StoryOrigin.BRANCH + "/.config/qits";
  }

  /** …and the file it then reads at the sha that listing resolved to. */
  private static String blobPath() {
    return "/git/" + REPO_ID + "/blob/" + publishedSha + "/" + TRIGGER_PATH;
  }

  /**
   * The runs recorded for one repository, newest first — read as a <b>person</b> rather than as a
   * machine. This service authenticates no human: the platform edge asserts {@code X-Qits-User} and
   * {@code X-Qits-Roles}, and the bearer-only OIDC tenant lets a request carrying no {@code
   * Authorization} header fall through to the header mechanism. Reading the listing that way is what
   * makes "an operator" an honest name for the arrow in the diagram.
   */
  private static List<Map<String, Object>> runsFor(String repoId) {
    return StoryIdentities.operator(given())
        .when()
        .get(StoryTarget.RUNS_PATH + "?repositoryId=" + repoId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("runs");
  }

  /**
   * Poll until the repository's one run has left {@code QUEUED}/{@code RUNNING}. The run executes on
   * qits-ci's own worker after the trigger answered, so a story that read once would be reading a
   * race rather than an outcome.
   */
  private static Map<String, Object> awaitTerminalRun(String repoId) {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = runsFor(repoId);
      if (runs.size() == 1) {
        Object status = runs.getFirst().get("status");
        assertNotNull(status);
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
          return runs.getFirst();
        }
      }
      sleep();
    }
    return fail("no terminal CI run for " + repoId + " within the deadline");
  }

  private static void sleep() {
    try {
      Thread.sleep(200);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
