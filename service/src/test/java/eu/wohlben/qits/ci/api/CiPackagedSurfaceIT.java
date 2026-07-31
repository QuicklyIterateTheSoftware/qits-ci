package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.daemonhost.FakeCiDaemon;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
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
 *   <li>the shipped datasource default connects — it is a file H2 opened by the process itself, and
 *       an {@code AUTO_SERVER=TRUE} on that URL is what first broke this binary (H2 starts its own
 *       TCP server, whose classes the image does not contain), invisibly to every other test here;
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

  /** The all-zero sha git reports as the old id of a newly created branch. */
  private static final String ZERO_SHA = "0".repeat(40);

  /**
   * The client's own spelling of the segment, from qits-spa-ci's {@code angular.json}. It is the
   * fingerprint every SPA probe here uses: "did this response come from the client" is a question
   * about the page, and the status code alone cannot answer it.
   */
  private static final String BASE_HREF = "<base href=\"/ci/\">";

  /**
   * Relocates the launched artifact's state under {@code target/} by moving {@code user.home}, not
   * by restating the settings. That is deliberate: ci's datasource URL and data-dir defaults are
   * {@code ${user.home}}-rooted in the ci jar's {@code META-INF/microprofile-config.properties}, so
   * overriding {@code user.home} leaves the <b>shipped</b> values themselves under test — including
   * the JDBC URL, which is where the native break lived (an {@code AUTO_SERVER=TRUE} that wants
   * H2's TCP server, a class the image does not contain). Spelling a URL out here instead would
   * have made this IT pass against a default no deployment can boot.
   *
   * <p>Only the git host is genuinely restated: its default points at a live qits-artifacts, and
   * this repo builds without one. The overrides ride to the process as {@code -D} arguments, so
   * every one of them has to be runtime config.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {
    static final Path HOME = Path.of("target", "ci-packaged-it-home").toAbsolutePath();
    static final Path GIT_HOST = Path.of("target", "ci-packaged-it-git-host").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
      deleteRecursively(HOME);
      deleteRecursively(GIT_HOST);
      return Map.of(
          "user.home", HOME.toString(),
          "qits.ci.git-host-url", "file://" + GIT_HOST);
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
  public void theIntakeIsAtTheAddressTheGitHostPostsTo() {
    // qits-artifacts' CiPostReceiveNotifier delivers here fire-and-forget: a wrong path raises no
    // error on either side and CI simply never runs, so the address is asserted from the artifact.
    // An empty body must reach @Valid — a 400 proves the resource, not the router's 404.
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/ci/api/events/post-receive")
        .then()
        .statusCode(400);
  }

  @Test
  public void theClientIsServedAtTheSegmentWithItsOwnBaseHref() {
    // The baseHref is set in qits-spa-ci's angular.json — another repository, where no build here
    // can check it. Disagree with quarkus.quinoa.ui-root-path and the page loads and then fetches
    // its own JavaScript from the wrong place, which is a failure with no error in it.
    String html =
        given()
            .when()
            .get("/ci/")
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
    // /ci/runs/<runId> is a real route in the client and must survive a hard reload — that is what
    // quarkus.quinoa.enable-spa-routing buys, and it only exists in the packaged process.
    String deepLink =
        given()
            .when()
            .get("/ci/runs/anything")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        deepLink.contains(BASE_HREF),
        "a deep link must answer with index.html, not with a differently-shaped page");
  }

  @Test
  public void theBareSegmentRedirectsRatherThanFourOhFouring() {
    // Quinoa mounts at /ci/*, which does not match the bare segment — WebUiRedirect is this
    // service's answer, and a raw Vert.x route only exists on the artifact's real router.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/ci")
        .then()
        .statusCode(301)
        .header("Location", "/ci/");
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

  @Test
  public void aPushRecordsARunThroughYamlFlywayAndPanache() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "ci-packaged", "steps: []\n");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("repoId", repoId, "branch", "ci-packaged", "oldSha", ZERO_SHA, "newSha", sha))
        .when()
        .post("/ci/api/events/post-receive")
        .then()
        .statusCode(202);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(sha, run.get("commitSha"));

    // The run above would look identical against an in-memory database, so pin that the process
    // really opened the ${user.home}-rooted file H2 the ci jar ships — that URL is the one this IT
    // is here to keep bootable.
    assertTrue(
        Files.isDirectory(PackagedUnderTarget.HOME.resolve(".qits/data/ci/h2")),
        "the shipped file-H2 default must be what the packaged process opened");
  }

  // --- the git host stands in as <base>/git/<repoId> over file://, as in CiPipelineBoundaryTest ---

  private Path gitHostRoot() {
    return PackagedUnderTarget.GIT_HOST.resolve("git");
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

  private String pushBranchWithConfig(String repoId, String branch, String config) throws Exception {
    Path clone = Files.createTempDirectory("ci-packaged-it-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitHostRoot().resolve(repoId).toString(), clone.toString());
    git(clone, "checkout", "-q", "-b", branch);
    Path configFile = clone.resolve(".config/qits/ci-post-receive.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, config);
    commitAll(clone, "add ci config");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", branch);
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

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception e) {
      throw new IllegalStateException("could not clear " + root, e);
    }
  }
}
