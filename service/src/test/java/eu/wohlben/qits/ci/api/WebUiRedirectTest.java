package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The bare segment redirects to the client; nothing else does. Quinoa is off under {@code %test},
 * which is exactly why this is testable here at all: the redirect is this service's own route, not
 * Quinoa's, and it must answer whether or not a client is packaged.
 */
@QuarkusTest
class WebUiRedirectTest {

  @Test
  void theBareSegmentRedirectsToTheClient() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/ci")
        .then()
        .statusCode(301)
        .header("Location", equalTo("/ci/"));
  }

  @Test
  void theQueryStringTravels() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/ci?run=abc")
        .then()
        .statusCode(301)
        .header("Location", equalTo("/ci/?run=abc"));
  }

  @Test
  void aWriteToTheBareSegmentIsMethodNotAllowed() {
    // The route matches the path but names GET and HEAD, so Vert.x answers 405 — the machine
    // client learns the truth instead of being bounced at HTML.
    given().redirects().follow(false).when().post("/ci").then().statusCode(405);
  }
}
