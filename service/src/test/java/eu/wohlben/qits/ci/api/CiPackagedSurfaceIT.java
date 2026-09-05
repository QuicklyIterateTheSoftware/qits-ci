package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.daemonhost.FakeCiDaemon;
import eu.wohlben.qits.ci.githost.StubGitHost;
import eu.wohlben.qits.ci.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The whole service as it is <b>packaged</b> — the fast-jar under a plain {@code mvn verify
 * -DskipITs=false}, and the GraalVM binary under {@code mvn verify -Dnative}. Every other test in
 * this repo runs in a JVM with the test classpath; this one starts the artifact a deployment
 * actually receives, which is the only way to catch the failure mode native-image has: reflection,
 * a classpath resource resolved by computed name, or a service loader that the closed-world build
 * dropped. Those leave the {@code @QuarkusTest} suite green and the binary broken at runtime.
 *
 * <p>So the assertions are chosen for what a native build can silently lose rather than for API
 * coverage (that is {@link CiPipelineBoundaryTest}'s job, and it stays the place to add behaviour):
 *
 * <ul>
 *   <li>the routes are where the config says — {@code quarkus.rest.path} and {@code
 *       quarkus.http.non-application-root-path} are <b>build-time</b> settings baked into the image,
 *       so a segment regression here can only be caught from the artifact;
 *   <li>the shipped datasource <b>expressions</b> resolve and connect. Both of them: this
 *       deployable's own {@code ci} store and the qits-eventstream jar's outbox, each reading a
 *       {@code QITS_RESOURCE_*} triple with no default behind it. The variables are what this IT
 *       supplies, never the datasource keys, so what is under test is the shipped file — which is
 *       where this repo's oldest native break lived (an {@code AUTO_SERVER=TRUE} that wanted H2's
 *       TCP server, a class the image does not contain) and where its container break lived too (a
 *       {@code ${user.home}} UID 1001 has no passwd entry for);
 *   <li>{@code db/ci/migration/V1__init.sql} survived as a resource and Flyway applied it — a
 *       migration is loaded by scanning a classpath location, exactly the shape native-image drops;
 *   <li>a real run reaches SnakeYAML and Panache: the intake queues, ci fetches the pushed commit
 *       with its own {@code git}, parses the committed config, and persists through Hibernate;
 *   <li>the ci-daemon control socket is on the artifact's router at {@code /ci/daemon} — a
 *       {@code @WebSocket} endpoint is registered by an extension at augmentation, so "websockets-next
 *       is native-image supported" is a claim this repo's rule says the binary has to prove rather
 *       than the documentation;
 *   <li>and, for the same reason, <b>how the Angular client and the machine surface divide {@code
 *       /ci}</b>. Quinoa is disabled by default in test mode, so no {@code @QuarkusTest} in this repo
 *       has ever seen the client at all — the SPA fallback, the {@code <base href>} another
 *       repository's {@code angular.json} sets, and every path {@code
 *       quarkus.quinoa.ignored-path-prefixes} must keep out of it are provable here or nowhere.
 * </ul>
 *
 * <p>That last group is qits-events' probe list ({@code docs/project-setup-quinoa-angular.md}),
 * adopted here because the asymmetry was the risk: this repo asserted the document, the intake, the
 * socket and a whole push-to-run round trip, and never that {@code /ci/} serves a page. Shipping the
 * client's first real pages behind that gap is how the fallback trap gets discovered in production —
 * a mistyped machine path answered 200 {@code index.html}, which a machine client parses as data.
 *
 * <p>The pipeline it pushes declares <b>no steps</b> — a config-less push records nothing at all, so
 * an empty {@code steps} list is the smallest config that still records a run, and it takes the path
 * through the parser and the database without needing docker. Step execution needs a container and
 * belongs to {@code CiDaemonGateIT} (tagged {@code extended}, and excluded from the native build
 * for that reason — see the root pom).
 */
@QuarkusIntegrationTest
@TestProfile(CiPackagedSurfaceIT.PackagedUnderTarget.class)
public class CiPackagedSurfaceIT {

  /**
   * The client's own spelling of its root, from qits-spa-ci's {@code angular.json}. It is the
   * fingerprint every SPA probe here uses: "did this response come from the client" is a question
   * about the page, and the status code alone cannot answer it.
   *
   * <p>It is {@code /} because this service has a host of its own: the client is what
   * {@code ci.<env>.<domain>} answers with, and {@code /ci} is left to the machine surface.
   */
  private static final String BASE_HREF = "<base href=\"/\">";

  /**
   * Hands the launched artifact its two databases the way a deployment does — as the generic
   * resource triples, not as the datasource keys. The ci jar ships {@code
   * jdbc.url=${QITS_RESOURCE_DB_URL}} and the qits-eventstream jar ships {@code
   * ${QITS_RESOURCE_EVENTSTREAM_URL}}, each with two siblings and no default behind any of them, so
   * supplying the VARIABLES leaves the <b>shipped</b> expressions themselves under test. Spelling
   * datasource urls out here instead would make this IT pass against defaults no deployment can
   * boot, which is exactly the failure this IT was written for.
   *
   * <p>The databases are an embedded postgres this JVM starts. <b>Their urls travel through system
   * properties rather than static fields</b>: a test profile is instantiated in more than one
   * classloader, so a field written by one copy is not the field the other reads, while the process
   * has exactly one property table.
   *
   * <p><b>The git host is NOT one of these, and it used to be.</b> This profile started a stub of
   * its own under {@code target/ci-packaged-it-git-host} and named it here — while {@link
   * StubGitHost} is also declared {@code @WithTestResource(scope = GLOBAL)} by the {@code
   * @QuarkusTest} classes, which makes it start for the whole test run, this one included, and a
   * test resource's properties OUTRANK a profile's overrides. So the launched process was always
   * pointed at the shared root while this class seeded a private one, every read 404'd, and the run
   * was discarded as a commit the repository no longer holds. Two servers where one silently loses
   * is worse than one: the stub is left to the resource that already runs it, and this class seeds
   * into {@link StubGitHost#ROOT}, which is the root that resource serves.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    /** Where each url is parked for whichever copy of this class is asked second. */
    private static final String CI_URL_PROPERTY = "qits.test.packaged-it.ci-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.packaged-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_DB_URL", databaseUrl(CI_URL_PROPERTY, "ci_packaged_it"),
          "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
          "QITS_RESOURCE_EVENTSTREAM_URL",
              databaseUrl(EVENTSTREAM_URL_PROPERTY, "eventstream_packaged_it"),
          "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
          "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
    }

    private static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheGatewaySegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /ci on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/ci/q/openapi").then().statusCode(200);
    given().when().get("/ci/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void theManualTriggerIsOnTheArtifactsRouter() {
    // The one write this service still serves, and a person's recovery door: a bootstrap replay
    // knocks on exactly this path. An empty body must reach the handler — the 400 is the resource
    // answering, where a moved prefix would be the router's 404.
    //
    // There used to be a probe for POST /ci/api/events/post-receive beside this one, asserted from
    // the artifact because a wrong path there raised no error on either side and CI simply stopped
    // running. That endpoint is gone: a push is an SCMPublishCommit off the event log now, and what
    // would be wrong is a listener that does not subscribe — which no HTTP probe can see.
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/ci/api/events/trigger")
        .then()
        .statusCode(400);
  }

  @Test
  public void theClientIsServedAtTheRootWithItsOwnBaseHref() {
    // The baseHref is set in qits-spa-ci's angular.json — another repository, where no build here
    // can check it. Disagree with quarkus.quinoa.ui-root-path and the page loads and then fetches
    // its own JavaScript from the wrong place, which is a failure with no error in it.
    String html =
        given()
            .when()
            .get("/")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        html.contains(BASE_HREF),
        "the client's baseHref must be the segment it is mounted at; got: "
            + html.substring(0, Math.min(400, html.length())));
  }

  @Test
  public void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    // /runs/<runId> is a real route in the client and must survive a hard reload — that is what
    // quarkus.quinoa.enable-spa-routing buys, and it only exists in the packaged process. The
    // scoped spelling is the same page reached through a repository, and it has to fall back too:
    // it is three segments the server knows nothing about.
    for (String deep : List.of("/runs/anything", "/qits/services/qits-ci/runs/anything")) {
      String deepLink =
          given()
              .when()
              .get(deep)
              .then()
              .statusCode(200)
              .contentType(ContentType.HTML)
              .extract()
              .asString();
      assertTrue(
          deepLink.contains(BASE_HREF),
          deep + " must answer with index.html, not with a differently-shaped page");
    }
  }

  @Test
  public void theSegmentIsTheMachineSurfaceAndNotTheClient() {
    // The bare segment used to redirect to /ci/, where the client lived. It does not live there any
    // more: /ci is the machine prefix, nothing serves it, and the SPA fallback is told to keep off
    // it — so the honest answer is a 404 rather than a web page. WebUiRedirect is gone with it.
    String bare = given().when().get("/ci").then().statusCode(404).extract().asString();
    assertFalse(bare.contains(BASE_HREF), "the bare segment must not be answered with the client");

    String slashed = given().when().get("/ci/").then().statusCode(404).extract().asString();
    assertFalse(
        slashed.contains(BASE_HREF), "the old client address must not be answered with the client");
  }

  @Test
  public void aMistypedMachinePathIsNeverAnsweredWithTheClient() {
    // The whole reason quarkus.quinoa.ignored-path-prefixes carries /api: without it this answers
    // 200 with index.html and a machine client parses the client's not-found page as data.
    //
    // "404, and not the CLIENT" rather than "404, never HTML": what comes back is Vert.x' own stock
    // `<h1>Resource not found</h1>`, which is text/html and correct. Nothing on this platform
    // installs a JSON 404 for unrouted paths, so asserting the content type alone would fail
    // against the right behaviour while still passing against the wrong one.
    String body = given().when().get("/ci/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains(BASE_HREF),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // And the same for the daemon socket's own prefix. Two probes on /ci/daemon rather than one,
    // because they fail for opposite reasons: websockets-next claims only the HANDSHAKE, so a plain
    // GET is the Quinoa question and the upgrade below is the augmentation question. This repo
    // learned that by measuring it — before the prefix was ignored, this answered 200 index.html.
    String daemon = given().when().get("/ci/daemon").then().statusCode(404).extract().asString();
    assertFalse(
        daemon.contains(BASE_HREF),
        "a plain GET on the daemon socket must not be answered with the client; got: " + daemon);
  }

  @Test
  public void theCiDaemonControlSocketIsOnTheArtifactsRouter() throws Exception {
    // Route presence, not behaviour: a step container's daemon dials this literal (it is
    // qits.ci.container-daemon-url's path) and a native build that silently dropped the endpoint
    // would leave every run stuck at "never registered" with nothing in any log to say why.
    //
    // The dial carries credentials the registry cannot know, so the assertion is: the upgrade
    // SUCCEEDS — proving the endpoint is registered and reachable at /ci/daemon — and the server
    // then closes it 1008. A missing route fails the upgrade instead, with a 404.
    URI socket = URI.create("http://localhost:" + RestAssured.port + "/ci/daemon");
    try (FakeCiDaemon daemon = FakeCiDaemon.dial(socket, "not-a-launched-daemon", "not-a-secret")) {
      assertEquals(
          (Short) (short) 1008,
          daemon.awaitClose(Duration.ofSeconds(20)),
          "the packaged artifact must serve /ci/daemon and refuse an unknown daemon on it");
    }
  }

  /**
   * A run recorded end to end on the packaged artifact: SnakeYAML parses the trigger file, Flyway's
   * migrations survived as resources, Panache writes the row, and the shipped datasource expression
   * resolved to the database this JVM injected.
   *
   * <p><b>It is driven by the manual trigger, and there is nothing else it could be driven by.</b>
   * A domain event arrives off the event log, and standing a real qits-events up beside a launched
   * artifact to deliver one would be a second integration entirely; {@code POST
   * /ci/api/events/trigger} builds the same {@code Arrival} the bus builds and records a run the same
   * way. (It was a push until 2026-09-05 and the swap was already forced then, for the same reason —
   * that intake has since retired outright.) So what this test can still see through the artifact's
   * own surface is unchanged: YAML, Flyway, Panache and the store it wrote to. The engine's own
   * semantics are a {@code @QuarkusTest}'s ({@code CiPipelineBoundaryTest}).
   */
  @Test
  public void aTriggeredRunGoesThroughYamlFlywayAndPanache() throws Exception {
    String repoId = seedOrigin();
    String eventName = "PackagedProbe";
    String sha =
        pushTriggerOnMain(
            repoId,
            "event: " + eventName + "\nwhen:\n  - repoId: { exact: " + repoId + " }\nsteps: []\n");

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name",
                eventName,
                "payload",
                Map.of("repoId", repoId)))
        .when()
        .post("/ci/api/events/trigger")
        .then()
        .statusCode(200);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(sha, run.get("commitSha"));

    // The run above proves the ci store. Read the row back out of the database this JVM handed the
    // process to pin WHICH store that was: the launched artifact resolved the shipped
    // ${QITS_RESOURCE_DB_URL} expression rather than falling back to anything, which is the whole
    // claim of the triple having no default.
    try (Connection ci =
            DriverManager.getConnection(
                EmbeddedPg.url("ci_packaged_it"), EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement rows =
            ci.prepareStatement("select count(*) from ci_run where commit_sha = ?")) {
      rows.setString(1, sha);
      try (ResultSet found = rows.executeQuery()) {
        assertTrue(found.next() && found.getInt(1) == 1, "the run must be in the injected database");
      }
    }
  }

  @Test
  public void theOutboxLineageIsInTheArtifactToo() throws Exception {
    // The second datasource, and the second Flyway lineage — the qits-eventstream jar's, migrated at
    // boot whether or not the bus is enabled. A packaged process that dropped it would fail at
    // startup rather than here, but the table is what says the migration resource survived.
    try (Connection outbox =
            DriverManager.getConnection(
                EmbeddedPg.url("eventstream_packaged_it"), EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement rows = outbox.prepareStatement("select count(*) from outbox_event")) {
      try (ResultSet found = rows.executeQuery()) {
        assertTrue(found.next(), "outbox_event must exist in the injected eventstream database");
      }
    }
  }

  // --- the git host is StubGitHost over <base>/git/<repoId>, as in CiPipelineBoundaryTest ---

  private Path gitHostRoot() {
    return StubGitHost.ROOT.resolve("git");
  }

  private String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path seed = Files.createTempDirectory("ci-packaged-it-seed");
    git(seed, "init", "-q", "-b", "main");
    Files.writeString(seed.resolve("hello.txt"), "hello\n");
    commitAll(seed, "initial");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  /**
   * Commits a trigger file on {@code main} and returns the head it left there — which is the commit
   * an event-triggered run builds, since an event names no ref and the tracked branch supplies one.
   */
  private String pushTriggerOnMain(String repoId, String trigger) throws Exception {
    Path clone = Files.createTempDirectory("ci-packaged-it-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitHostRoot().resolve(repoId).toString(), clone.toString());
    Path triggerFile = clone.resolve(".config/qits/ci-event-packaged.yml");
    Files.createDirectories(triggerFile.getParent());
    Files.writeString(triggerFile, trigger);
    commitAll(clone, "add ci event trigger");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", "main");
    return sha;
  }

  private void commitAll(Path clone, String message) throws Exception {
    git(clone, "add", ".");
    git(clone, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", message);
  }

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
