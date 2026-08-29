package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link CiPackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove. The shipped tenant is
 * gated: {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, and every suite in
 * this repository leaves that gate shut — {@code MachineGuardTest} comes closest and stops exactly
 * where this one starts, because it flips the gate but installs its identity with
 * {@code @TestSecurity} + {@code @OidcSecurity} claims, so nothing there ever fetches a key or
 * validates a signature. The block this service actually deploys with (auth-server-url plus {@code
 * jwks-path=jwks} against a real listener, audience enforcement, the {@code groups} claim becoming
 * roles) is therefore exercised nowhere else. The far side is {@link MockIdp}, whose recordings make
 * the interaction assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted under
 * {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories are
 * browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's transitive
 * Playwright never launches anything.
 *
 * <p><b>ITs stay skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because the three docker-backed gates — {@code CiDaemonHandshakeIT},
 * {@code CiDaemonGateIT}, {@code CiDaemonContainerProbeIT} — bind to the same failsafe run and need
 * real docker, a published step image, a built daemon binary and a container route back to this JVM.
 * The root pom's {@code qits.it.excluded-groups} would exclude them by their {@code extended} tag,
 * but it is <b>empty by default</b> and only the {@code native} profile sets it, deliberately, so
 * that {@code -DskipITs=false} still means "run everything". Naming the class is therefore the only
 * opt-in that is correct on a plain {@code verify}, and it is what {@code
 * .config/qits/ci-event-userflows.yml} passes:
 *
 * <pre>{@code ./mvnw verify -DskipITs=false -Dit.test=TokenValidationBootstrapIT}</pre>
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-qits-ci-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-ci-run-listing";

  /** The route both stories present a bearer to. See the accept story for why it is this one. */
  static final String GUARDED_ROUTE = "/ci/api/runs/active";

  /**
   * {@link CiPackagedSurfaceIT.PackagedUnderTarget} — the two {@code QITS_RESOURCE_*} triples on
   * this JVM's embedded postgres, parked in system properties because a test profile is instantiated
   * in more than one classloader — <b>plus the two things this story is about</b>: the gate that
   * turns the shipped OIDC tenant on, and where the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-ci needs in order to boot
   * at all is one answer, it is written out at length over there — including why the VARIABLES are
   * supplied rather than the datasource keys, so the shipped {@code ${QITS_RESOURCE_DB_URL}}
   * expressions stay the ones under test — and a second copy of the parking trick would be a second
   * place for it to drift. What is added here is only the seam this test moves, plus the three
   * outbound dials a host-run process has no deployment behind.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()}, which
   * parks its coordinates (and its keypair) in system properties for the same classloader reason —
   * that is also how the story method's {@link MockIdp#attach()} reaches the very server the
   * launched process fetched its keys from.
   *
   * <p><b>Every key below is a RUNTIME key.</b> A packaged process takes its configuration as
   * {@code -D} arguments on a jar that was already built, so a build-time key here would be silently
   * ignored and the test would prove the opposite of what it says — which is this repository's own
   * worst defect class rather than a theoretical hazard (see AGENTS.md on {@code AUTO_SERVER} and
   * the {@code ${user.home}} outbox url).
   */
  public static class PackagedWithMockIdp extends CiPackagedSurfaceIT.PackagedUnderTarget {

    /**
     * The audience this service enforces, and it is a LITERAL rather than a variable name.
     * {@code qits.auth.machine.audience=qits-ci} is spelled out in {@code application.properties}
     * — set there rather than left to a deployment because a service that accepted tokens addressed
     * elsewhere would be broken, not configured differently — so the audience under test is the
     * shipped one and there is no expression to feed. {@code
     * quarkus.oidc.token.audience=${qits.auth.machine.audience}} is what carries it to quarkus-oidc,
     * so minting against this string is also what proves that indirection is read.
     */
    static final String AUDIENCE = "qits-ci";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());

      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that does not. It is
      // the posture a deployed platform takes, and this story is where it is documented. Flipping
      // the derived key directly would prove the tenant and skip the seam.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam this test MOVES: where the idp is. A runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and `jwks-path=jwks` is joined onto it.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

      // --- the three dials a host-run process has no deployment behind --------------------------
      // Dark outside a deployment, like %dev/%test — both runtime keys. The eventstream DATASOURCE
      // is still opened and migrated (dark stops publishing, sweeping and dialling, never the
      // datasource), which is why the second triple the parent supplies is not optional.
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      // The daemon pin ladder's startup discovery is a SEPARATE HTTP call to qits.events.url and is
      // not covered by the key above — application.properties gives it its own %dev/%test switch for
      // exactly that reason, and a launched artifact runs under neither profile. Off here for the
      // same reason it is off there: there is no qits-events to dial, and an adoption landing
      // mid-story would be state nothing asked for behind the listing this story reads.
      overrides.put("qits.ci.daemon-autoadopt-enabled", "false");
      // The orchestrator stand-in, and it stands nothing up — the same address, and the same
      // argument, as the suite's own test properties. The boot reap is a StartupEvent observer that
      // runs outside TEST mode, so a launched artifact really does ask an orchestrator to delete
      // this owner's step containers; pointed at the shipped qits-containers alias it could reach a
      // real one on the developer's own machine. Nothing answers on port 1, the reap gives up after
      // its patience window with one WARN, and boot proceeds — which is the documented behaviour of
      // an orchestrator that is not up yet.
      overrides.put("qits.containers.url", "http://127.0.0.1:1");
      return overrides;
    }
  }

  @UserStory(
      value = "On start, qits-ci fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-ci must validate service bearers before any caller arrives: at
      startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery stays off,
      the path is configured — so the very first machine request is judged on the platform's own
      keys. The callers that depend on it are the ones that cannot log in: the platform
      orchestrator polling what CI is doing, and the chrome asking a repository for its runs.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note("qits-ci starts with the OIDC tenant on, beside a reachable qits-platform-idp");
    given().get("/ci/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That ordering is the whole claim. A service that fetched keys lazily, on the
    // first bearer, would look identical from this end and fail its first caller after a restart —
    // which is exactly what quarkus.oidc.connection-delay=30S exists to prevent, and what a
    // bootstrap's first trigger once paid for.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.happened("qits-ci", "qits-platform-idp", "GET /idp/jwks (at startup)").as("jwks-fetched");

    // End (b), the ci side: those keys are what token validation now runs on. A platform service's
    // bearer (aud = this service, roles in `groups`) opens the guarded run listing.
    //
    // GET /ci/api/runs/active is the right door for this story on three counts. It is a plain read
    // of ci's own rows — no git host, no qits-containers, no qits-events, so what it proves is the
    // token and not another service's availability. It is class-level {qits:admin, qits:system} on
    // CiRunController, so the machine role a platform peer really holds is enough (the write beside
    // it, cancelRun, carries its own method-level qits:admin, which REPLACES the class list). And
    // it takes no parameters at all, so an empty answer is still a 200 and the story stays about
    // who may read rather than about what happens to be in the table.
    String platformToken =
        idp.token()
            .subject("qits-platform-orchestrator")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("runs", notNullValue());
    story
        .happened(
            "a platform service",
            "qits-ci",
            "GET /ci/api/runs/active (Bearer, groups=[qits:system])")
        .as("runs-served");
  }

  @UserStory(
      value = "A stranger's token never opens the CI run listing",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks. Both are 401 and not 403: the credential never became an identity, so
      there is no caller to have been forbidden. The audience half is the one worth stating out
      loud, because every service on qits-net is issued tokens by the same idp — qits-ci itself
      asks for two, one for qits-containers and one for qits-githost — and neither of those may
      open this door.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-ci",
            "GET /ci/api/runs/active (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    // qits-containers and not an invented name: it is an audience this service's own oidc-client
    // really requests (quarkus.oidc-client.grant-options.client.audience), so the story documents
    // the confusion that could actually happen on qits-net rather than a strawman.
    String wrongAudienceToken =
        idp.token().audience("qits-containers").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-ci",
            "GET /ci/api/runs/active (another service's audience) -> 401")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY, ACCEPTED_SLUG, "qits-ci", "qits-platform-idp", "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "runs-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
