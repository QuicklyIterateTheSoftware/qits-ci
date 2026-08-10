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
  @TestSecurity(user = ARTIFACTS)
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
  @TestSecurity(user = ARTIFACTS)
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
  @TestSecurity(user = ARTIFACTS)
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
  @TestSecurity(user = ARTIFACTS)
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
  void readsAreNotGuarded() {
    given().when().get("/ci/api/repositories").then().statusCode(200);
    // Including the daemon pin, which the artifacts GC reads from qits-net holding no intake token.
    given().when().get("/ci/api/daemon").then().statusCode(200);
  }
}
