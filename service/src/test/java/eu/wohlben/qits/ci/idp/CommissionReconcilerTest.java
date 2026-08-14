package eu.wohlben.qits.ci.idp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The leftovers sweep: what qits-idp still holds, minus what a run still owns.
 *
 * <p>Driven through {@link CommissionReconciler#reap} with the run ids handed in, which is the seam
 * the boot pass and the schedule both reach through — the read behind them is one Panache query and
 * belongs to the run table's own tests. What is qits-ci's own here is the <b>predicate</b>: which
 * rows are this feature's, which are still owed, and what a listing nobody could read reaps.
 */
public class CommissionReconcilerTest {

  private StubIdp idp;
  private CommissionReconciler reconciler;

  @BeforeEach
  void wire() {
    idp = new StubIdp();
    reconciler = new CommissionReconciler();
    reconciler.idp = idp.commissioner(Duration.ofMillis(200));
    reconciler.commissions = idp.runCommissions(Duration.ofMillis(200));
  }

  @AfterEach
  void stop() {
    idp.close();
  }

  private static IdpCommissioner.LiveClient row(String clientId, String kind, String contextId) {
    return new IdpCommissioner.LiveClient(clientId, kind, contextId);
  }

  @Test
  public void aCredentialWhoseRunIsOverIsReapedAndALiveRunsIsLeftAlone() {
    int reaped =
        reconciler.reap(
            List.of(row("client-dead", "ci-run", "run-dead"), row("client-live", "ci-run", "run-live")),
            Set.of("run-live"));

    // The whole feature in one assertion: what a killed process left behind goes, what a running
    // build is pushing with stays.
    assertEquals(1, reaped);
    assertEquals(List.of("client-dead"), idp.deleted);
  }

  @Test
  public void aCommissionThisProcessIsHoldingIsSparedEvenWithNoRunRowLeft() {
    // The window between a run's row going terminal and its runClosed. The row says the run is over
    // and the credential is still in use, so memory outranks the table here.
    IdpCommissioner.Commission held = reconciler.commissions.forRun("run-finishing");

    int reaped = reconciler.reap(List.of(row(held.clientId(), "ci-run", "run-finishing")), Set.of());

    assertEquals(0, reaped);
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aCommissionOfAnotherContextKindIsNoneOfThisSweepsBusiness() {
    // The listing is this owner's whole set, and qits-ci may one day commission for something that
    // is not a run. A sweep that reaped by owner alone would take those with it.
    int reaped =
        reconciler.reap(List.of(row("client-workspace", "workspace", "ws-1")), Set.of());

    assertEquals(0, reaped);
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aListingNobodyCouldReadReapsNothing() {
    // "Nothing was learned" must never read as "no run owns anything" — that would delete every live
    // build's credential the first time qits-idp answered badly. An unreadable listing is an empty
    // Optional, and reconcile returns before it even asks which runs are live.
    idp.listingBody = "{\"not\":\"an array\"}";
    reconciler.reconcile();
    assertEquals(List.of(), idp.deleted);
    assertTrue(idp.listings.get() > 0, "it did ask");

    // And the same for a run table that could not be read: a null id set reaps nothing.
    assertEquals(0, reconciler.reap(List.of(row("client-dead", "ci-run", "run-dead")), null));
    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aRealListingIsReadOffTheWireAndThenReaped() {
    idp.listingBody =
        "[{\"clientId\":\"client-dead\",\"owner\":\"dev-qits-ci\",\"contextKind\":\"ci-run\","
            + "\"contextId\":\"run-dead\",\"createdAt\":\"2026-08-14T10:00:00Z\"}]";

    List<IdpCommissioner.LiveClient> live = reconciler.idp.live().orElseThrow();

    assertEquals(List.of(row("client-dead", "ci-run", "run-dead")), live);
    assertEquals(1, reconciler.reap(live, Set.of("run-other")));
    assertEquals(List.of("client-dead"), idp.deleted);
  }
}
