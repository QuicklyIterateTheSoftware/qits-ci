package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import eu.wohlben.qits.ci.control.FakeDaemonProbe;
import eu.wohlben.qits.ci.persistence.CiDaemonPinRepository;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.DurableFunnel;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three bus listeners as <b>durable</b> consumers: what the claim ledger settles, what stays
 * owed, and what a late arrival is allowed to do.
 *
 * <p>Driven through {@link DurableFunnel} directly rather than over the socket, which is the same
 * choice {@code CiEventTriggerCausationTest} and {@code DaemonReleaseListenerTest} make one level up
 * — the arriving is somebody else's test. It also buys the one thing dispatch cannot give: the
 * funnel <em>answers</em>, so "handled once" and "still owed" are assertions rather than inferences
 * from a side effect. The funnel is also exactly what the catch-up sweeper calls, so a second offer
 * of one frame IS a sweep reading past an event the stream already delivered.
 *
 * <p>What is not asserted here is the paging, the watermark and the pruning. Those are the library's
 * and its suite holds them; a copy here would test qits-eventstream through qits-ci.
 *
 * <p>Reuses {@link BuildSuccessfulPublishTest.EventstreamOn} — the funnel is a no-op with the module
 * dark, by design — and declares nothing else, so this class shares the application {@code
 * DaemonReleaseListenerTest} already starts rather than costing a second Quarkus boot.
 */
@QuarkusTest
@TestProfile(BuildSuccessfulPublishTest.EventstreamOn.class)
@WithTestResource(StubEventsServer.class)
public class DurableBusConsumptionTest {

  private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

  @Inject DurableFunnel funnel;

  @Inject BuildSuccessfulListener builds;

  @Inject CiEventTriggerListener triggers;

  @Inject DaemonReleaseListener daemon;

  @Inject CiDaemonPins pins;

  @Inject CiDaemonPinRepository repo;

  @Inject FakeDaemonProbe probe;

  @BeforeEach
  void resetState() {
    StubEventsServer.reset();
    probe.reset();
    QuarkusTransaction.requiringNew().run(repo::deleteAll);
    ClientProxy.unwrap(pins).configuredVersion = Optional.empty();
  }

  // --- BuildSuccessfulListener: ci-release-train ---

  /**
   * The claim, on the listener whose effect is smallest — which is why it is the clearest place to
   * see it. Two offers of one event, one run of the handler.
   */
  @Test
  public void aBuildSuccessfulIsHandledOnceAcrossADuplicateDelivery() {
    EventFrame frame = buildFrame(anId(), "some-repo");

    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(builds, frame));
    assertEquals(
        DurableFunnel.Result.SKIPPED,
        funnel.offer(builds, frame),
        "the claim row is what a catch-up sweep finds when it reaches an event the stream delivered");
  }

  /**
   * Poison tolerance. A payload that will not bind reads the same on every offer, so a throw would
   * hand this event back forever and hold the watermark behind it — the seam has no dead letter and
   * says so. Swallowed with a WARN means {@code HANDLED}: settled, and the sweep moves on.
   */
  @Test
  public void aBuildSuccessfulWithAnUnreadablePayloadIsSettledRatherThanOwed() {
    EventFrame frame =
        new EventFrame(anId(), "BuildSuccessful", T0, "this is not json {", null, null);

    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(builds, frame));
  }

  // --- CiEventTriggerListener: ci-event-triggers ---

  /**
   * The duplicate-enqueue question the migration had to answer: one event reaches the trigger engine
   * at most once, whatever mix of live frame and catch-up row produced the arrivals. The run-row
   * unique constraint is still underneath this and is still the thing that survives a race; the
   * claim is what stops the second evaluation from being started at all.
   */
  @Test
  public void aTriggerEventIsEnqueuedOnceAcrossADuplicateDelivery() {
    EventFrame frame = buildFrame(anId(), "some-upstream");

    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(triggers, frame));
    assertEquals(DurableFunnel.Result.SKIPPED, funnel.offer(triggers, frame));
  }

  /**
   * {@code "*"} permanently, and {@code selects} left at its default with it — the engine's real
   * selection is an HTTP fan-out at the git host, which is neither pure nor cheap and must not sit in
   * front of the claim. Pinned because the pair is a decision with a price (a claim row per event on
   * the bus) rather than an omission.
   */
  @Test
  public void theTriggerEngineSubscribesToEverythingAndNarrowsNowhere() {
    assertEquals(Set.of(QitsRawEventListener.ALL), triggers.signatures());
    assertTrue(triggers.selects(buildFrame(anId(), "any-repo")));
  }

  /**
   * The one thing this listener swallows. No trigger file can declare a nameless event, so no later
   * offer could ever match one; failing would wedge this consumer's catch-up on it forever.
   */
  @Test
  public void aFrameWithNoNameIsSettledRatherThanOwed() {
    EventFrame nameless = new EventFrame(anId(), null, T0, "{}", null, null);

    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(triggers, nameless));
  }

  // --- DaemonReleaseListener: ci-daemon-adopt ---

  @Test
  public void aDaemonReleaseIsAdoptedOnceAcrossADuplicateDelivery() throws Exception {
    probe.willAnswer("2026.801.101010", Verdict.PROVEN, "");
    EventFrame frame = daemonFrame(anId(), "2026.801.101010", T0);

    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(daemon, frame));
    assertEquals(DurableFunnel.Result.SKIPPED, funnel.offer(daemon, frame));
    daemon.awaitIdle();

    assertEquals(1, repo.count());
    assertEquals("2026.801.101010", pins.answer().version());
  }

  /**
   * <b>The late-delivery guard, which is the whole reason this listener needed thinking about.</b>
   * Catch-up delivers out of stream order, so after a restart this handler can be handed a release
   * that is older than the one already pinned. Adopting it would roll the daemon backwards. {@link
   * CiDaemonPins#adopt}'s freshness check — not newer than the newest adopted, so ignored — is the
   * tip check, and the event is still settled: it was genuinely handled, and the handling was to
   * decline.
   */
  @Test
  public void aLateDaemonReleaseIsSettledWithoutRollingThePinBackwards() throws Exception {
    probe.willAnswer("2026.802.120000", Verdict.PROVEN, "");
    probe.willAnswer("2026.801.090000", Verdict.PROVEN, "");

    funnel.offer(daemon, daemonFrame(anId(), "2026.802.120000", T0.plusSeconds(3600)));
    daemon.awaitIdle();
    assertEquals("2026.802.120000", pins.answer().version());

    assertEquals(
        DurableFunnel.Result.HANDLED,
        funnel.offer(daemon, daemonFrame(anId(), "2026.801.090000", T0)),
        "an older release is handled and declined, not retried");
    daemon.awaitIdle();

    assertEquals(1, repo.count(), "the older candidate never became a rung");
    assertEquals("2026.802.120000", pins.answer().version());
  }

  /**
   * Selective storage: a release for anything but this one daemon leaves <b>no row at all</b>, which
   * is what keeps the claim ledger proportional to the adoptions rather than to every artifact the
   * platform publishes.
   */
  @Test
  public void aReleaseForAnotherArtifactIsNotSelected() throws Exception {
    EventFrame image =
        new EventFrame(
            anId(),
            "SoftwareRelease",
            T0,
            "{\"packageName\":\"qits/qits-stt\",\"packageType\":\"docker\",\"repository\":\"some-repo\""
                + ",\"version\":\"1.4.0\"}",
            null,
            null);

    assertFalse(daemon.selects(image));
    assertEquals(DurableFunnel.Result.SKIPPED, funnel.offer(daemon, image));
    daemon.awaitIdle();

    assertEquals(0, repo.count());
  }

  /**
   * Poison, answered in the predicate rather than in the handler. A {@code selects} that <em>threw</em>
   * would leave the event owed — the seam treats an undecidable selection as a failure on purpose —
   * so an unreadable payload has to answer "no" instead, which settles it with no row.
   */
  @Test
  public void aSoftwareReleaseWithAnUnreadablePayloadIsDeclinedRatherThanOwed() throws Exception {
    EventFrame broken =
        new EventFrame(anId(), "SoftwareRelease", T0, "not json at all", null, null);

    assertFalse(daemon.selects(broken), "a predicate that throws leaves the event owed forever");
    assertEquals(DurableFunnel.Result.SKIPPED, funnel.offer(daemon, broken));
    daemon.awaitIdle();

    assertEquals(0, repo.count());
  }

  /**
   * The other poison case, and the one that has to be caught in the handler: the ladder orders
   * candidates by {@code occurredAt} and {@code adopt} throws without one. A throw here is retried
   * forever, so it is a WARN and a settled event instead.
   */
  @Test
  public void aDaemonReleaseWithNoOccurredAtIsSettledUnadopted() throws Exception {
    EventFrame undated = daemonFrame(anId(), "2026.801.111111", null);

    assertEquals(DurableFunnel.Result.HANDLED, funnel.offer(daemon, undated));
    daemon.awaitIdle();

    assertEquals(0, repo.count());
  }

  // --- frames, in the canonical alphabetical-key shape the wire uses ---

  private static String anId() {
    return UUID.randomUUID().toString();
  }

  private static EventFrame buildFrame(String eventId, String repoId) {
    return new EventFrame(
        eventId,
        "BuildSuccessful",
        T0,
        "{\"branch\":\"main\",\"commitSha\":\"cafebabe\",\"repoId\":\"" + repoId + "\"}",
        null,
        null);
  }

  private static EventFrame daemonFrame(String eventId, String version, Instant occurredAt) {
    return new EventFrame(
        eventId,
        "SoftwareRelease",
        occurredAt,
        "{\"packageName\":\"qits-ci-daemon\",\"packageType\":\"daemon\",\"repository\":"
            + "\"qits-ci-daemon\",\"version\":\""
            + version
            + "\"}",
        null,
        null);
  }
}
