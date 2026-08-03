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
import java.util.List;
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
  public void startupDiscoveryReadsTwoRowsAndSeedsBothRungs() throws Exception {
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
    listener.awaitIdle();

    CiDaemonPins.Pin pin = pins.answer();
    assertEquals("v-new", pin.version());
    assertEquals("v-old", pin.previousVersion());
    assertEquals(CiDaemonPins.SOURCE_ADOPTED, pin.source());
  }

  /**
   * The regression for the boot deadlock this class once had: {@code answer()}'s probe launches a
   * container that dials back to this process over a socket the startup thread has not bound yet, so
   * a synchronous probe inside {@code onStart} could never succeed and blocked boot until cd killed
   * the deployment (qits-ci {@code 0e09ca32}, 2026.803.171135). {@code reconcileFromLog} must adopt
   * and return without probing; the probe belongs on {@code ci-daemon-adopt-worker}, proven here by
   * asserting {@link FakeDaemonProbe} was not touched until {@link DaemonReleaseListener#awaitIdle}
   * drains that queue.
   */
  @Test
  public void reconcileFromLogAdoptsAndReturnsWithoutProbingSynchronously() throws Exception {
    StubEventsServer.seedEvent(
        "evt-boot",
        "2026-08-01T00:00:00Z",
        "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\",\"repository\":\"qits-ci-daemon\""
            + ",\"version\":\"v-boot\"}");
    probe.willAnswer("v-boot", Verdict.PROVEN, "");

    listener.reconcileFromLog();

    assertTrue(
        probe.probed().isEmpty(),
        "reconcileFromLog must return before the probe runs -- this is the boot deadlock shape");
    assertEquals(1, repo.count(), "the release is adopted (a fast upsert) even though unprobed");

    listener.awaitIdle();

    assertEquals(List.of("v-boot"), probe.probed(), "the probe runs once queued idle drains");
  }

  /**
   * {@code onStart} itself, not just {@code reconcileFromLog} -- the same claim from the actual
   * {@code @Observes StartupEvent} entry point, so a future change that moves the probe back inline
   * in {@code onStart} (rather than in the method it delegates to) still fails this test.
   */
  @Test
  public void onStartReturnsWithoutInvokingTheProbeSynchronously() throws Exception {
    StubEventsServer.seedEvent(
        "evt-onstart",
        "2026-08-01T00:00:00Z",
        "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\",\"repository\":\"qits-ci-daemon\""
            + ",\"version\":\"v-onstart\"}");
    probe.willAnswer("v-onstart", Verdict.PROVEN, "");
    ClientProxy.unwrap(listener).autoadoptEnabled = true;
    try {
      listener.onStart(null);

      assertTrue(probe.probed().isEmpty(), "onStart must return before the probe runs");

      listener.awaitIdle();

      assertEquals(List.of("v-onstart"), probe.probed());
    } finally {
      ClientProxy.unwrap(listener).autoadoptEnabled = false;
    }
  }

  /**
   * Readiness right after boot, with the ladder's one adopted candidate still unprobed and a
   * configured pin underneath it: {@link eu.wohlben.qits.ci.api.CiDaemonReadinessCheck} is DOWN only
   * when the ladder falls all the way through (source {@code NONE}) -- the configured pin is a bottom
   * rung, never probed and never demoted, so it is available whatever the adopted candidate turns out
   * to be. Scripted {@code REJECTED} here to prove the fallback, not just the happy path.
   */
  @Test
  public void bootTimeReadinessIsUpWithAnUnprobedLadderBehindAConfiguredPin() throws Exception {
    ClientProxy.unwrap(pins).configuredVersion = Optional.of("v-configured");
    StubEventsServer.seedEvent(
        "evt-unprobed",
        "2026-08-01T00:00:00Z",
        "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\",\"repository\":\"qits-ci-daemon\""
            + ",\"version\":\"v-unprobed\"}");
    probe.willAnswer("v-unprobed", Verdict.REJECTED, "never dialled");

    listener.reconcileFromLog();
    assertTrue(
        probe.probed().isEmpty(),
        "the moment a container healthcheck would hit this endpoint after boot: adopted, not probed");

    given()
        .when()
        .get("/ci/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", is("UP"))
        .body("checks.find { it.name == 'ci-daemon-pin' }.status", is("UP"));

    listener.awaitIdle();
    assertEquals("v-configured", pins.answer().version());
    assertEquals(CiDaemonPins.SOURCE_CONFIGURED, pins.answer().source());
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
