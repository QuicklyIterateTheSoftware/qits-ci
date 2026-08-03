package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiDaemonPins.Pin;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import eu.wohlben.qits.ci.entity.CiDaemonPinVerdict;
import eu.wohlben.qits.ci.persistence.CiDaemonPinRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The daemon pin ladder's own logic (ci-daemon-autoadopt-plan.md §2.1-§2.6), driven against a
 * scripted {@link DaemonProbe} rather than a real container -- the container half is workstream
 * BW's. Every test starts from an empty table and a blank configured pin, so "the ladder falls
 * straight through" is always the starting point rather than something a previous test left behind.
 */
@QuarkusTest
public class CiDaemonPinsTest {

  private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

  @Inject CiDaemonPins pins;
  @Inject CiDaemonPinRepository repo;
  @Inject FakeDaemonProbe probe;

  @BeforeEach
  void resetLadder() {
    QuarkusTransaction.requiringNew().run(repo::deleteAll);
    probe.reset();
    ClientProxy.unwrap(pins).configuredVersion = Optional.empty();
  }

  private void configurePin(String version) {
    ClientProxy.unwrap(pins).configuredVersion = Optional.of(version);
  }

  @Test
  public void withNothingAdoptedAndNothingConfiguredTheAnswerIsBlank() {
    assertEquals(new Pin("", "", CiDaemonPins.SOURCE_NONE), pins.answer());
  }

  @Test
  public void withNothingAdoptedTheConfiguredPinAnswers() {
    configurePin("2026.803.91607");
    assertEquals(new Pin("2026.803.91607", "", CiDaemonPins.SOURCE_CONFIGURED), pins.answer());
  }

  @Test
  public void anAdoptedCandidateThatProvesItselfOutranksTheConfiguredPin() {
    configurePin("2026.803.91607");
    probe.willAnswer("2026.803.99999", Verdict.PROVEN, "");
    pins.adopt("2026.803.99999", UUID.randomUUID().toString(), T0);

    assertEquals(
        new Pin("2026.803.99999", "", CiDaemonPins.SOURCE_ADOPTED), pins.answer());
  }

  @Test
  public void theLadderWalksDownPastARejectedCandidate() {
    // Two adopted candidates, the newer one broken -- the fallback the whole feature exists for.
    probe.willAnswer("v-old", Verdict.PROVEN, "");
    probe.willAnswer("v-new", Verdict.REJECTED, "never dialled");
    pins.adopt("v-old", "event-old", T0);
    pins.adopt("v-new", "event-new", T0.plusSeconds(60));

    Pin answer = pins.answer();
    assertEquals("v-old", answer.version());
    assertEquals(CiDaemonPins.SOURCE_ADOPTED, answer.source());
  }

  @Test
  public void previousDaemonVersionIsTheNextProvenAdoptedCandidate() {
    probe.willAnswer("v-old", Verdict.PROVEN, "");
    probe.willAnswer("v-new", Verdict.PROVEN, "");
    pins.adopt("v-old", "event-old", T0);
    pins.adopt("v-new", "event-new", T0.plusSeconds(60));

    assertEquals(new Pin("v-new", "v-old", CiDaemonPins.SOURCE_ADOPTED), pins.answer());
  }

  @Test
  public void previousDaemonVersionIsBlankWhenTheTopPinIsTheConfiguredOne() {
    // The ladder's bottom rung has no rung below it -- previousDaemonVersion must not fall through
    // to a second read of the same configured value.
    configurePin("2026.803.91607");
    assertEquals("", pins.answer().previousVersion());
  }

  @Test
  public void anUnknownVerdictNeverBecomesThePin() {
    configurePin("2026.803.91607");
    probe.willAnswer("v-new", Verdict.UNKNOWN, "no docker on this host");
    pins.adopt("v-new", "event-new", T0);

    // Fail-closed toward the status quo: an UNKNOWN candidate is skipped exactly like a REJECTED
    // one, and the ladder falls through to the configured pin rather than ever answering v-new.
    assertEquals(
        new Pin("2026.803.91607", "", CiDaemonPins.SOURCE_CONFIGURED), pins.answer());
  }

  @Test
  public void theConfiguredPinIsNeverDemoted() {
    configurePin("2026.803.91607");
    probe.willAnswer("v-new", Verdict.REJECTED, "never dialled");
    pins.adopt("v-new", "event-new", T0);

    // Every adopted candidate rejected, and the configured pin -- never itself a row, never itself
    // probed -- still answers.
    assertEquals(
        new Pin("2026.803.91607", "", CiDaemonPins.SOURCE_CONFIGURED), pins.answer());
  }

  @Test
  public void anOlderOccurredAtIsIgnoredRatherThanTreatedAsADemotion() {
    probe.willAnswer("v-new", Verdict.PROVEN, "");
    probe.willAnswer("v-old", Verdict.PROVEN, "");
    pins.adopt("v-new", "event-new", T0.plusSeconds(60));
    // A late-delivered release whose event is OLDER than the newest already-adopted candidate: it
    // must be ignored, never treated as a second, lower rung -- calver is never parsed or compared.
    pins.adopt("v-old", "event-old", T0);

    assertTrue(repo.findByVersion("v-old").isEmpty(), "an older release must not be adopted at all");
    assertEquals(new Pin("v-new", "", CiDaemonPins.SOURCE_ADOPTED), pins.answer());
  }

  @Test
  public void adoptionIsIdempotentOnEventId() {
    String eventId = UUID.randomUUID().toString();
    probe.willAnswer("v-new", Verdict.PROVEN, "");
    pins.adopt("v-new", eventId, T0);
    // A redelivery of the same event, even naming a different version -- the idempotency key is the
    // event id, and a second arrival must change nothing.
    pins.adopt("v-other", eventId, T0.plusSeconds(60));

    assertEquals(1, repo.count());
    assertEquals("v-new", repo.findByEventId(eventId).orElseThrow().version);
  }

  @Test
  public void aVersionContainingASlashOrDotDotIsRefused() {
    pins.adopt("../etc/passwd", "event-1", T0);
    pins.adopt("a/b", "event-2", T0.plusSeconds(1));
    pins.adopt("..", "event-3", T0.plusSeconds(2));

    assertEquals(0, repo.count(), "no hostile version may become a row");
    assertTrue(probe.probed().isEmpty(), "a refused version must never reach the probe");
  }

  @Test
  public void aCandidateIsProbedAtMostOnce() {
    probe.willAnswer("v-new", Verdict.PROVEN, "");
    pins.adopt("v-new", "event-new", T0);

    pins.answer();
    pins.answer();

    assertEquals(1, probe.probed().size(), probe.probed().toString());
  }

  /**
   * Fix 2's single-flight guard, proven concurrently rather than sequentially -- {@link
   * #aCandidateIsProbedAtMostOnce} above proves a different thing: a durable PROVEN verdict is never
   * reprobed across two sequential calls. This proves that two callers racing the same
   * still-unproven candidate at the same time collapse into one probe, which is what today's
   * incident needed: two concurrent probes of the same candidate raced docker's own container-name
   * uniqueness.
   */
  @Test
  public void concurrentAnswersForTheSameStillUnprovenCandidateCollapseIntoOneProbe()
      throws InterruptedException {
    probe.willAnswer("v-race", Verdict.PROVEN, "");
    probe.blockOn("v-race");
    pins.adopt("v-race", "event-race", T0);

    Thread first = new Thread(pins::answer, "answer-1");
    first.start();
    // Wait for the first call to actually be inside the probe -- not just started -- before starting
    // the second, so the second is guaranteed to arrive while the single-flight guard is held.
    probe.awaitEntered();

    Thread second = new Thread(pins::answer, "answer-2");
    second.start();
    second.join(5_000);
    assertFalse(
        second.isAlive(),
        "a caller that loses the single-flight race must return promptly, never wait on the probe");

    probe.release();
    first.join(5_000);
    assertFalse(first.isAlive(), "the winning probe must complete once released");

    assertEquals(1, probe.probed().size(), probe.probed().toString());
    assertEquals(new Pin("v-race", "", CiDaemonPins.SOURCE_ADOPTED), pins.answer());
  }

  /**
   * Fix 4: {@code UNKNOWN} is a statement about the probe, not the candidate, so a later call
   * reprobes it -- and Fix 2's write-guard widening is what lets the retry's verdict actually
   * persist, rather than being silently dropped because the row no longer reads {@code UNPROVEN}.
   */
  @Test
  public void anUnknownVerdictIsReprobedAndTheRetryCanWin() {
    probe.willAnswer("v-retry", Verdict.UNKNOWN, "docker refused: container name conflict");
    pins.adopt("v-retry", "event-retry", T0);

    assertEquals(new Pin("", "", CiDaemonPins.SOURCE_NONE), pins.answer());
    assertEquals(CiDaemonPinVerdict.UNKNOWN, verdictOf("v-retry"));

    // Re-script the SAME candidate to PROVEN -- the point is that the retry actually happens and
    // actually wins, not merely that it is attempted.
    probe.willAnswer("v-retry", Verdict.PROVEN, "");
    assertEquals(new Pin("v-retry", "", CiDaemonPins.SOURCE_ADOPTED), pins.answer());
    assertEquals(CiDaemonPinVerdict.PROVEN, verdictOf("v-retry"));
    assertEquals(2, probe.probed().stream().filter("v-retry"::equals).count(), probe.probed().toString());
  }

  /** A fresh read of one row's verdict, in its own transaction -- reading the {@code repo} field
   *  directly a second time for the same id, with no transaction of its own in between, can return a
   *  Hibernate session's already-cached (and by then stale) copy of the entity rather than what a
   *  later write actually persisted. A fresh {@code requiringNew} forces a real read, the same way
   *  every production read in {@link CiDaemonPins} already does. */
  private CiDaemonPinVerdict verdictOf(String version) {
    return QuarkusTransaction.requiringNew()
        .call(() -> repo.findByVersion(version).orElseThrow().verdict);
  }

  /**
   * The inverse of the test above: {@code REJECTED} is a statement about the candidate itself and
   * stays terminal even once rescripted to {@code PROVEN} -- unlike {@code UNKNOWN}, a rejected
   * candidate must never be given a second chance.
   */
  @Test
  public void aRejectedVerdictStaysTerminalEvenIfRescriptedToProven() {
    probe.willAnswer("v-rejected", Verdict.REJECTED, "capability mismatch");
    pins.adopt("v-rejected", "event-rejected", T0);

    pins.answer();
    probe.willAnswer("v-rejected", Verdict.PROVEN, "");
    pins.answer();

    assertEquals(
        1,
        probe.probed().stream().filter("v-rejected"::equals).count(),
        "a REJECTED candidate must never be reprobed: " + probe.probed());
    assertEquals(CiDaemonPinVerdict.REJECTED, verdictOf("v-rejected"));
  }
}
