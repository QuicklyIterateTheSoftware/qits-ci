package eu.wohlben.qits.ci.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * The deployed posture (dev-user fallback blanked): the gateway-injected header is the identity, and
 * its absence is anonymous rather than denied.
 *
 * <p>That last point is the one worth stating. Nothing in this service denies anything — there is no
 * authorization policy here, by design (migration-auth-plan.md §12). So every assertion below is
 * about <em>who the request is</em>, never about a status code. A test that expected a 401 would be
 * asserting a security control this service does not have and must not grow.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class ForwardAuthTest {

  @Test
  void theGatewayInjectedHeaderEstablishesTheIdentity() {
    given()
        .header("X-Qits-User", "alice")
        .when()
        .get("/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo("alice"));
  }

  @Test
  void noHeaderIsAnonymousAndStillServed() {
    // Anonymous is "no name for the audit row", not a security state — the request proceeds.
    given()
        .when()
        .get("/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void aBlankHeaderIsAnonymousNotAnEmptyPrincipal() {
    given()
        .header("X-Qits-User", "  ")
        .when()
        .get("/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void theIdentityCarriesNoRoles() {
    // The gateway emits no groups header and makes the one role decision the system has (§4.3), so
    // a role arriving here would mean something upstream had started asserting one.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Groups", "admin")
        .when()
        .get("/api/test-identity")
        .then()
        .statusCode(200)
        .body("principal", equalTo("alice"))
        .body("roles", empty());
  }
}
