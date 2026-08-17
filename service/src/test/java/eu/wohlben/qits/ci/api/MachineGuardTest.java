package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.QitsClaims;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The write surface with the machine gate ON — the deployment posture once qits-idp exists.
 *
 * <p><b>There is one write left, and that is the whole of what this file now says.</b> It used to
 * cover two: the push intake, guarded with the pushed repository, and the manual trigger, guarded
 * with every project. The intake is gone — a push arrives as {@code SCMPublishCommit} off the event
 * log, where a bearer would mean nothing, since what authenticates an event is the bus that carried
 * it rather than a header on a request nobody makes. The cases that asked "may this token push to
 * this repository" have no endpoint left to ask it of.
 *
 * <p>The rule they enforced is unchanged, and is why this file stays: a NEW write method that simply
 * omits {@code machineAuth.require*} ships unguarded and nothing says so. Add a write endpoint, add
 * its case here, and keep every address absolute — a moved prefix then shows up as a 404 rather than
 * as a pass.
 *
 * <p>The identity is installed by {@code @TestSecurity} rather than signed by a running idp on
 * purpose. What is under test is this service's decision about a token's claims; whether a signature
 * or an expiry is checked is quarkus-oidc's contract, tested where it lives. A test issuer here
 * would only re-assert the extension.
 *
 * <p><b>The reads are no longer open, and three doors now shut in order.</b> No token at all is a
 * 401. A token granted no roles is a 403 at {@code @RolesAllowed} — qits-platform-idp copies a
 * client's {@code roles} into the token's {@code groups} claim and quarkus-oidc reads that claim as
 * roles with no configuration at all, so a token minted without it authenticates and covers
 * nothing. Only then is {@code MachineAuth} asked, and a wrong audience or an uncovered project is
 * its own 403. Which caller each read serves is spelled out per case: the daemon pin takes the two
 * system roles a machine peer holds, and the repository listing takes {@code qits:admin}, which
 * only a forwarded {@code X-Qits-Roles} carries.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class MachineGuardTest {

  /** The audience this service's machine guard expects — its config default, injected in prod. */
  private static final String OWN_AUDIENCE = "qits-ci";

  /** A valid platform audience that is not ours; the guard must refuse it. */
  private static final String FOREIGN_AUDIENCE = "prod-qits-deployments";

  /**
   * The calling client every case below installs — qits-artifacts, which reads the daemon pin from
   * qits-net. It is a subject, never an audience, and no case asserts it: what is under test is the
   * token's claims, and the caller only has to be some machine.
   *
   * <p>Spelled here rather than taken from {@code QitsClaims}, like the two audiences above and for
   * the same reason. It used to be {@code QitsClaims.ARTIFACTS}, the last service id that library
   * held; the byte-plane split deleted it, because every service is an environment service now and
   * an id carries its environment — {@code <env>-qits-artifacts} — which no constant can know.
   */
  private static final String ARTIFACTS = "prod-qits-artifacts";

  /**
   * The gate on, and the {@code %test} dev user off.
   *
   * <p>Blanking the dev user is not a convenience — it is what makes the suite match a deployment.
   * {@code ForwardAuthMechanism} answers every request that carries no {@code X-Qits-User} with the
   * synthetic {@code dev} identity, so under {@code %test} it authenticates first and no other
   * mechanism is ever asked. A real machine call has no such header and no such fallback: forward
   * auth abstains and the bearer is what the request is judged on. Left set, every test below sees a
   * user rather than a machine and passes only by answering 401 for the wrong reason.
   */
  public static class GateOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(MachineAuth.REQUIRED_KEY, "true", "qits.auth.forward.dev-user", "");
    }
  }

  /**
   * The machine roles qits-platform-idp grants a platform service client, copied into the token's
   * {@code groups} claim from {@code qits.idp.client.<id>.roles}. quarkus-oidc reads that claim as
   * the identity's roles with no configuration at all, which is what lets a machine caller satisfy
   * the {@code @RolesAllowed("qits:system")} the guarded controllers carry.
   *
   * <p><b>{@code @TestSecurity} replaces the identity wholesale rather than adding to it</b>, so a
   * case that names only the user installs a caller granted nothing: it authenticates and is then
   * refused 403, which is the shape {@link #aTokenGrantedNoRolesIs403} asserts on purpose. An
   * annotation takes only constant expressions, so the pair below is spelled out at every use.
   */
  private static final String SYSTEM = "qits:system";

  /** The platform-wide half of the same grant, held by the same clients. */
  private static final String PLATFORM_SYSTEM = "qits-platform:system";

  /** A person's role, which the edge forwards in {@code X-Qits-Roles} and no machine token holds. */
  private static final String ADMIN = "qits:admin";

  /** Absolute, like every address here: it is what catches a prefix or a rename regression. */
  private static final String TRIGGER = "/ci/api/events/trigger";

  private static final String TRIGGER_BODY =
      """
      {"name":"SoftwareRelease","payload":{"repository":"guarded-repo","version":"1.0.0"}}""";

  @Test
  void theManualTriggerWithNoMachineTokenIs401() {
    // 401, not 403: nothing was presented, so the answer is "present something". The forward-auth
    // dev user is blanked in this profile and would make no difference anyway — a user is not a
    // machine.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theManualTriggerNeedsATokenGrantedEveryProject() {
    // 503 is the guard PASSING here, and it is deterministic in this profile: the trigger evaluates
    // before it answers, this instance has no git host (qits.ci.git-host-url answers on nothing), so
    // no candidate repository can be read and the endpoint says so rather than inventing a 2xx. What
    // a dropped or tightened guard looks like is 401 or 403, which is what this case rules out.
    // The endpoint's own contract is CiManualTriggerTest's.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(503);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = FOREIGN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aTokenMintedForAnotherServiceIs403() {
    // Granted everything, and addressed elsewhere. The audience is the half of the guard that says
    // "this token is for me"; a platform where it were optional would let any service's token act
    // here.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aTokenGrantedNoProjectClaimIs403() {
    // An absent claim is a mismatch, never a wildcard.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "guarded-repo")
      })
  void aTokenScopedToOneRepositoryMayNotTriggerAcrossAllOfThemIs403() {
    // An event names no repository — it is evaluated against every candidate — so a grant naming one
    // does not cover it.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aTokenGrantedNoRolesIs403() {
    // A client id qits-platform-idp knows with no `.roles` line beside it mints exactly this:
    // correctly signed, addressed here, granted every project, and carrying an empty `groups`
    // claim. It authenticates and covers nothing, because @RolesAllowed shuts before MachineAuth is
    // ever asked. A 403 rather than the 401 an absent token gets, which is what tells a missing idp
    // grant from a missing sender.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theDaemonPinIsAMachinePeersReadRatherThanAnOpenOne() {
    // qits-platform-artifacts' collector reads the pin before it deletes a daemon binary. It holds
    // this service's audience and the two system roles, so the read answers it — and the endpoint
    // is closed to everyone else, which is the contract rather than an oversight.
    given().when().get("/ci/api/daemon").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theRepositoryListingIsAPersonsReadAndRefusesAMachine() {
    // qits-spa-ci draws this listing, so it takes qits:admin — a role only a forwarded
    // X-Qits-Roles carries. An impeccable machine token is refused 403 here: the reads used to be
    // open and are not, and which caller each one serves is the whole of what this case says.
    given().when().get("/ci/api/repositories").then().statusCode(403);
  }

  @Test
  void aForwardedAdminSessionReadsTheRepositoryListing() {
    // The other side of the same route, and the reason no @TestSecurity is here: the headers ARE
    // the contract. The edge establishes the session and qits-gateway asserts the pair, which is
    // what this profile's blanked dev user makes visible.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", ADMIN)
        .when()
        .get("/ci/api/repositories")
        .then()
        .statusCode(200);
  }
}
