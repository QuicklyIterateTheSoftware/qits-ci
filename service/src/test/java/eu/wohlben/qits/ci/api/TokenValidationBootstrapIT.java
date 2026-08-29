package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.stories.support.MockContainers;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

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
 * <p>It is also this repo's <b>first</b> userflow — the rest of the catalogue is under {@code
 * …ci.stories}, where the build flows live: the proof doubles as documentation, emitted under
 * {@code target/userstories/} with a network diagram beside the steps. The diagram is <b>observed,
 * never narrated</b> — {@link NetworkTaps#restAssured} taps what a story sends into this service,
 * {@link MockIdp}'s recordings supply what this service sent to the idp, and the framework drains
 * both at story end. A story method therefore asserts and notes; it draws nothing. Both stories are
 * browserless (an {@code Interactions} parameter and no {@code Flow}), so the framework's transitive
 * Playwright never launches anything.
 *
 * <p><b>That tap used to be a copy in this repository.</b> {@code StoryNetworkFilter} sat beside
 * this class, twenty hand-copied lines naming this service and its probe root; the framework ships
 * it now, idempotent per service, so every story class installs the same one from its own {@code
 * @BeforeAll} and no repository has a private opinion about what a probe is.
 *
 * <p><b>The two stories are ordered</b>, and that is load-bearing rather than tidiness: a cumulative
 * source is attributed by a cursor, so traffic that happened before any story ran — the startup JWKS
 * fetch, which is the whole subject of the first story — lands in whichever story drains
 * <i>first</i>. Pinning the order is what keeps that the story it belongs to.
 *
 * <p><b>The same argument reaches across classes, which is why this one runs first.</b> Its profile
 * is the one every story class in this repository shares — one {@code @TestProfile} is one launched
 * process for the whole failsafe phase — so they are a single group to Quarkus' profile-aware class
 * orderer and the secondary orderer breaks the tie by class name: {@code
 * …ci.api.TokenValidationBootstrapIT} sorts before {@code …ci.stories.*}. The story classes say so
 * explicitly with {@code @UserflowRunsAfter} as well, because a JWKS fetch drained into a build
 * story would empty the story it documents.
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG = "on-start-qits-ci-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-ci-run-listing";

  /** The route both stories present a bearer to. See the accept story for why it is this one. */
  static final String GUARDED_ROUTE = "/ci/api/runs/active";

  /** How the diagram names this service on both sides of an edge. */
  static final String SERVICE = "qits-ci";

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
      // The orchestrator, played by a recording stand-in. This used to be http://127.0.0.1:1 — an
      // address nothing answers on — because the only thing an auth story needed of qits-containers
      // was that the boot reap give up and let boot proceed. The build stories need the opposite:
      // a step container is four HTTP calls to this address and NOTHING ELSE, so a stand-in that
      // answers them is the whole of what stands between a story and a real build, and the
      // credential the socket story dials with exists only in the workload spec that arrives here.
      //
      // It is in THIS profile rather than in a second one on purpose: a @TestProfile is what
      // decides whether failsafe launches another process, so every seam every IT class needs lives
      // in the one profile they all share and the whole phase costs one launched qits-ci.
      //
      // The boot reap is still a StartupEvent observer that runs outside TEST mode — a launched
      // artifact really does ask an orchestrator to delete this owner's step containers — and it now
      // gets an answer instead of a refusal. See MockContainers on why that call is nobody's story.
      overrides.put("qits.containers.url", MockContainers.baseUrl());
      // …and the patience stays short. It was shrunk when nothing answered, because the two
      // 60-second windows collide exactly: the reap holds through "nothing answering" for its full
      // PT60S on a StartupEvent observer, the "Listening on" line prints only after observers
      // return, and failsafe's launcher waits 60s for precisely that line — so at the shipped
      // patience the launcher declares the process dead moments before boot would have proceeded.
      // Measured on this IT's first CI run (2026-08-29). A stand-in that answers immediately never
      // enters that window, and one second is what keeps the collision from coming back if it stops.
      overrides.put("qits.ci.containers.boot-reap-patience", "PT1S");
      // A configured daemon pin, so /ci/q/health/ready is UP. The ci-daemon-pin @Readiness check is
      // DOWN whenever the pin ladder has SOURCE_NONE — no adopted release AND no configured version
      // — which is exactly an isolated boot with no qits-events to adopt from. A deployment carries a
      // pin (autoadopted or configured); this is the configured arm, which is what turns the pin's
      // source away from NONE and the check UP without dialling anything. The value is a plausible
      // CalVer and never resolved — no image is pulled here — it only has to be non-blank. Without
      // it the story's own readiness beat (and cd's health gate, in prod) reads the service as not
      // ready, which is true and beside the point of an auth story.
      overrides.put("qits.ci.daemon-version", "2026.101.000000");
      return overrides;
    }
  }

  /**
   * Wires both halves of the network diagram, once, before either story runs.
   *
   * <p>{@link NetworkTaps#restAssured} is the near side (what a story sends here) — the framework's
   * own tap, which replaced this repo's hand-copied {@code StoryNetworkFilter}: the same twenty
   * lines, the same {@code /q/} segment skip (right for this service, whose non-application root is
   * {@code /ci/q}), idempotent per service so every story class may install it from its own {@code
   * @BeforeAll}. The idp is the far
   * side, registered as a <b>cumulative</b> source: the supplier hands over the mock's whole request
   * log every time it is asked and the framework remembers how much of it earlier stories already
   * consumed, so the startup fetch — recorded long before any story existed — is attributed to the
   * first story and to that one only. It is invoked lazily at story end, so registering it here is
   * safe even though nothing has been recorded yet.
   *
   * <p>The label carries the status the mock <i>answered</i> with, which is the half a method and
   * path cannot supply: {@code "GET /idp/jwks -> 200"} is evidence that the keys were served, not
   * merely asked for.
   */
  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
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
  @Order(1)
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
    story
        .note("the signing keys were fetched at startup, before this story presented any token")
        .as("jwks-fetched");

    // End (b), the ci side: those keys are what token validation now runs on. A platform service's
    // bearer (aud = this service, roles in `groups`) opens the guarded run listing.
    //
    // The actor is set BEFORE the call: the tap sees a request, never a narrative role, and this is
    // what makes the observed edge read `a platform service -> qits-ci`.
    //
    // GET /ci/api/runs/active is the right door for this story on three counts. It is a plain read
    // of ci's own rows — no git host, no qits-containers, no qits-events, so what it proves is the
    // token and not another service's availability. It is class-level {qits:admin, qits:system} on
    // CiRunController, so the machine role a platform peer really holds is enough (the write beside
    // it, cancelRun, carries its own method-level qits:admin, which REPLACES the class list). And
    // it takes no parameters at all, so an empty answer is still a 200 and the story stays about
    // who may read rather than about what happens to be in the table.
    NetworkCapture.actor("a platform service");
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
        .note("a platform service's bearer (aud=qits-ci, groups=[qits:system]) is accepted")
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
  @Order(2)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    // Everything this story sends is an impostor's, so the actor is set once, up front.
    NetworkCapture.actor("an impostor");

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
    // Both refusals are the same edge — same actor, same route, same status — so the diagram draws
    // one arrow and the notes are what keep the two credentials distinguishable. That is the right
    // division: the graph says who reached what and got what, the steps say why.
    story
        .note("a token signed by a key the published JWKS never carried is refused")
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
        .note("a token minted for another service's audience (qits-containers) is refused too")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete now also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    // Observed on the far side, drained from the mock's recording, and attributed to this story
    // because it is the first one that ran (see the class javadoc on ordering).
    ReportAssertions.assertEdge(
        CATEGORY, ACCEPTED_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    // Observed on the near side, by the filter, with the actor this story set.
    ReportAssertions.assertEdge(
        CATEGORY,
        ACCEPTED_SLUG,
        "http",
        "a platform service",
        SERVICE,
        "GET " + GUARDED_ROUTE + " -> 200");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "runs-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, DENIED_SLUG, "http", "an impostor", SERVICE, "GET " + GUARDED_ROUTE + " -> 401");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
