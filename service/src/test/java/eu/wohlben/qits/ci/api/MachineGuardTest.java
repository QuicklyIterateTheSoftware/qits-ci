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
 * The event intake with the machine gate ON — the deployment posture once qits-idp exists.
 *
 * <p>Its twin is {@code CiPipelineBoundaryTest}, which POSTs the same address with no credential at
 * all and expects 202. That is the SHIPPED default, and the two together are the whole claim about
 * this endpoint: gate off, nothing changed; gate on, a token decides.
 *
 * <p>The identity is installed by {@code @TestSecurity} rather than signed by a running idp on
 * purpose. What is under test is this service's decision about a token's claims; whether a signature
 * or an expiry is checked is quarkus-oidc's contract, tested where it lives. A test issuer here
 * would only re-assert the extension.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class MachineGuardTest {

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

  private static final String INTAKE = "/ci/api/events/post-receive";

  private static String push(String repoId) {
    return """
        {"repoId":"%s","branch":"main","oldSha":"%s","newSha":"%s"}"""
        .formatted(repoId, "0".repeat(40), "1".repeat(40));
  }

  @Test
  @TestSecurity(user = QitsClaims.ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = QitsClaims.CI),
        @Claim(key = QitsClaims.PROJECT, value = "guarded-repo")
      })
  void aTokenNamingThisRepositoryIsAccepted() {
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(push("guarded-repo"))
        .when()
        .post(INTAKE)
        .then()
        .statusCode(202);
  }

  @Test
  @TestSecurity(user = QitsClaims.ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = QitsClaims.CI),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aWildcardTokenIsAcceptedForAnyRepository() {
    // How the git host actually holds its grant: it serves every repository, so it is granted every
    // one rather than a list that would have to be edited per repository.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(push("some-repo-nobody-named"))
        .when()
        .post(INTAKE)
        .then()
        .statusCode(202);
  }

  @Test
  @TestSecurity(user = QitsClaims.ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = QitsClaims.CI),
        @Claim(key = QitsClaims.PROJECT, value = "some-other-repo")
      })
  void aTokenNamingAnotherRepositoryIs403() {
    // Authenticated and addressed to this service — it simply may not act on this repository.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(push("guarded-repo"))
        .when()
        .post(INTAKE)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = QitsClaims.ARTIFACTS)
  @OidcSecurity(claims = {@Claim(key = "aud", value = QitsClaims.CI)})
  void aTokenGrantedNoProjectClaimIs403() {
    // An absent claim is a mismatch, never a wildcard.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(push("guarded-repo"))
        .when()
        .post(INTAKE)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = QitsClaims.ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = QitsClaims.CD),
        @Claim(key = QitsClaims.PROJECT, value = "guarded-repo")
      })
  void aTokenMintedForAnotherServiceIs403() {
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(push("guarded-repo"))
        .when()
        .post(INTAKE)
        .then()
        .statusCode(403);
  }

  @Test
  void noMachineTokenAtAllIs401() {
    // 401, not 403: nothing was presented, so the answer is "present something". The forward-auth
    // dev user is in scope here and makes no difference — a user is not a machine.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(push("guarded-repo"))
        .when()
        .post(INTAKE)
        .then()
        .statusCode(401);
  }

  @Test
  void aMalformedEventIsStill400WithNoToken() {
    // Validation runs before the guard, so a broken payload is not a way to probe the guard.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"repoId\":\"\",\"branch\":\"main\",\"newSha\":\"\"}")
        .when()
        .post(INTAKE)
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = QitsClaims.ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = QitsClaims.CI),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void readsAreNotGuarded() {
    given().when().get("/ci/api/repositories").then().statusCode(200);
  }
}
