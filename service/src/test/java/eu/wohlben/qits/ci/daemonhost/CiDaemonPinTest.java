package eu.wohlben.qits.ci.daemonhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /ci/api/daemon} — the pin surface: what daemon binary a run started right now would
 * download.
 *
 * <p>Addressed on its <b>absolute</b> path, like every other route test here, so a
 * {@code quarkus.rest.path} regression fails rather than hides. Note how close the two spellings sit:
 * this resource is {@code /ci/api/daemon} and the control socket is {@code /ci/daemon}.
 *
 * <p><b>It lives in {@code daemonhost} rather than beside the resource in {@code api}, and that buys
 * one Quarkus start instead of two.</b> The endpoint answers the configured value, so proving it
 * means asking with the config blank and with it set — and a second value normally means a second
 * {@code @TestProfile}, which means a second application boot racing the test port. From this
 * package the launcher's own field is reachable, so the set case is staged through {@link
 * ClientProxy#unwrap} the way {@code CiDaemonGateIT} stages the container url, and restored after.
 * A deliberate, local ugliness and not a pattern to spread.
 */
@QuarkusTest
public class CiDaemonPinTest {

  private static final String DAEMON = "/ci/api/daemon";

  /** The shape the shipped url template addresses the binary by: 64 lowercase hex characters. */
  private static final String A_DIGEST =
      "c04a603e95cf1f2f6a9a1f6f0f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c";

  @Inject CiDaemonLauncher launcher;

  @AfterEach
  void restoreTheShippedDefault() {
    ClientProxy.unwrap(launcher).daemonVersion = Optional.empty();
  }

  @Test
  public void anUnpinnedDeploymentAnswersBlankRatherThanOmittingTheField() {
    // Blank is the shipped default and an answer in its own right: this platform has published no
    // daemon yet. The field is present so a caller can tell "no pin" from "this service is older
    // than the endpoint", which are different things and must not look alike.
    given().when().get(DAEMON).then().statusCode(200).body("daemonVersion", is(""));
  }

  @Test
  public void theConfiguredPinIsWhatIsAnswered() {
    ClientProxy.unwrap(launcher).daemonVersion = Optional.of(A_DIGEST);

    // The CONFIGURED value, never a run row: ci_run.daemon_version is history, and the run listing
    // clamps at 100, so the rows cannot even enumerate what this instance has pinned. This answers
    // what a run started right now would download, which is the question the artifacts GC asks
    // before it plans a sweep.
    given().when().get(DAEMON).then().statusCode(200).body("daemonVersion", is(A_DIGEST));
  }
}
