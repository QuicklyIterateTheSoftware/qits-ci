package eu.wohlben.qits.ci.bus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import eu.wohlben.qits.ci.control.FakeDaemonProbe;
import eu.wohlben.qits.ci.persistence.CiDaemonPinRepository;
import eu.wohlben.qits.eventstream.control.EventDispatcher;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DaemonReleaseListener} and {@link CiDaemonReadinessCheck}, end to end inside one JVM: a
 * real frame down the typed path, a real adoption, a real (scripted) probe, and the readiness
 * surface an operator or cd's own health gate reads. {@link EventsDaemonReleaseLog}'s happy path is
 * proven here too, against {@link StubEventsServer}; its one claim that needs no server at all --
 * an unreachable qits-events answers empty rather than throwing -- is {@code
 * EventsDaemonReleaseLogTest}'s, plain JUnit.
 *
 * <p>Reuses {@link BuildSuccessfulPublishTest.EventstreamOn} rather than declaring a second profile
 * -- a second {@code @TestProfile} is a second Quarkus start, and this class wants the same
 * application {@code BuildSuccessfulPublishTest} and {@code CiEventTriggerCausationTest} already
 * share. Frames are handed to {@link EventDispatcher} directly, the same choice those two classes
 * make: a routing claim is about dispatch, not about the wire.
 */
@QuarkusTest
@TestProfile(BuildSuccessfulPublishTest.EventstreamOn.class)
@WithTestResource(StubEventsServer.class)
public class DaemonReleaseListenerTest {

  private final ObjectMapper json = new ObjectMapper();

  @Inject DaemonReleaseListener listener;

  @Inject CiDaemonPins pins;

  @Inject CiDaemonPinRepository repo;

  @Inject EventDispatcher dispatcher;

  @Inject FakeDaemonProbe probe;

  @BeforeEach
  void resetState() {
    StubEventsServer.reset();
    probe.reset();
    QuarkusTransaction.requiringNew().run(repo::deleteAll);
    ClientProxy.unwrap(pins).configuredVersion = Optional.empty();
  }

  @AfterEach
  void restoreTheShippedDefault() {
    ClientProxy.unwrap(pins).configuredVersion = Optional.empty();
  }

  @Test
  public void aSoftwareReleaseForADockerArtifactIsIgnored() throws Exception {
    dispatcher.dispatch(
        releaseFrame("evt-docker", "docker", "qits/qits-stt", "some-repo", "1.4.0", "2026-08-01T00:00:00Z"));
    listener.awaitIdle();

    assertEquals(0, repo.count(), "a non-daemon package must never reach the ladder");
  }

  @Test
  public void aSoftwareReleaseForTheDaemonAdopts() throws Exception {
    probe.willAnswer("2026.803.91607", Verdict.PROVEN, "");
    dispatcher.dispatch(
        releaseFrame(
            "evt-daemon",
            "daemon",
            CiDaemonPins.DAEMON_NAME,
            "qits-ci-daemon",
            "2026.803.91607",
            "2026-08-01T00:00:00Z"));
    listener.awaitIdle();

    CiDaemonPins.Pin pin = pins.answer();
    assertEquals("2026.803.91607", pin.version());
    assertEquals(CiDaemonPins.SOURCE_ADOPTED, pin.source());
  }

  @Test
  public void startupDiscoveryReadsTwoRowsAndSeedsBothRungs() {
    // StubEventsServer answers GET in the order events were seeded (its own javadoc says so) --
    // qits-events itself answers newest first, so the seed order here is newest first too.
    StubEventsServer.seedEvent(
        "evt-new",
        "2026-08-02T00:00:00Z",
        "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\",\"repository\":\"qits-ci-daemon\""
            + ",\"version\":\"v-new\"}");
    StubEventsServer.seedEvent(
        "evt-old",
        "2026-08-01T00:00:00Z",
        "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\",\"repository\":\"qits-ci-daemon\""
            + ",\"version\":\"v-old\"}");
    probe.willAnswer("v-old", Verdict.PROVEN, "");
    probe.willAnswer("v-new", Verdict.PROVEN, "");

    listener.reconcileFromLog();

    CiDaemonPins.Pin pin = pins.answer();
    assertEquals("v-new", pin.version());
    assertEquals("v-old", pin.previousVersion());
    assertEquals(CiDaemonPins.SOURCE_ADOPTED, pin.source());
  }

  @Test
  public void readinessGoesDownWhenEverythingIsRejectedAndUpOnTheNextAcceptedRelease()
      throws Exception {
    probe.willAnswer("v-bad", Verdict.REJECTED, "never dialled");
    dispatcher.dispatch(
        releaseFrame(
            "evt-bad", "daemon", CiDaemonPins.DAEMON_NAME, "qits-ci-daemon", "v-bad",
            "2026-08-01T00:00:00Z"));
    listener.awaitIdle();

    assertEquals("", pins.answer().version(), "no configured pin and every rung rejected");
    given()
        .when()
        .get("/ci/q/health/ready")
        .then()
        .statusCode(503)
        .body("status", is("DOWN"))
        .body("checks.find { it.name == 'ci-daemon-pin' }.status", is("DOWN"))
        .body("checks.find { it.name == 'ci-daemon-pin' }.data.rejectedVersions", containsString("v-bad"));

    probe.willAnswer("v-good", Verdict.PROVEN, "");
    dispatcher.dispatch(
        releaseFrame(
            "evt-good", "daemon", CiDaemonPins.DAEMON_NAME, "qits-ci-daemon", "v-good",
            "2026-08-01T00:01:00Z"));
    listener.awaitIdle();

    assertEquals("v-good", pins.answer().version());
    given()
        .when()
        .get("/ci/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", is("UP"))
        .body("checks.find { it.name == 'ci-daemon-pin' }.status", is("UP"));
  }

  /** A {@code SoftwareRelease} frame, exactly the shape {@code CiEventTriggerCausationTest}'s own
   *  release-fan-out test builds -- alphabetical payload keys, an explicit JSON {@code null} for
   *  {@code description} and {@code parentId}. */
  private String releaseFrame(
      String eventId,
      String packageType,
      String packageName,
      String repository,
      String version,
      String occurredAt)
      throws Exception {
    String payload =
        "{\"packageName\":\""
            + packageName
            + "\",\"packageType\":\""
            + packageType
            + "\",\"repository\":\""
            + repository
            + "\",\"version\":\""
            + version
            + "\"}";
    return "{\"id\":\""
        + eventId
        + "\",\"name\":\"SoftwareRelease\",\"occurredAt\":\""
        + occurredAt
        + "\",\"payload\":"
        + json.writeValueAsString(payload)
        + ",\"description\":null,\"parentId\":null}";
  }
}
