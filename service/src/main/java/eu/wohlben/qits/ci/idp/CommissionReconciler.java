package eu.wohlben.qits.ci.idp;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Deletes the commissioned credentials no run owns any more — the durable half of {@link
 * RunCommissions}, which can only give back what this process is still holding.
 *
 * <p><b>What it is for.</b> A commission is released when the run closes, and a process that is
 * killed mid-run closes nothing: the credential stays live at qits-idp with no run behind it. So
 * does one whose {@code DELETE} failed, and one belonging to a run this instance never entered. The
 * list of what exists is qits-idp's, the list of what is owed is the run table, and the difference
 * between them is what this reaps.
 *
 * <p><b>The predicate is deliberately narrow.</b> Only rows of this owner's — the listing shows no
 * other client's — whose {@code contextKind} is {@code ci-run}, and whose {@code contextId} is not a
 * run that is {@code QUEUED} or {@code RUNNING} right now. A commission this process holds in memory
 * is spared as well, which covers the window between a run's row going terminal and its {@code
 * runClosed}.
 *
 * <p><b>A listing that could not be read reaps nothing.</b> {@link IdpCommissioner#live()} answers
 * an empty {@code Optional} rather than an empty list for that case, and reading the two as one
 * would delete every live run's credential the first time qits-idp was slow — the same "a read
 * failure must not shrink a set" rule the candidate listing and the run queue already state.
 *
 * <p><b>Boot, on its own thread.</b> The observer runs after both existing boot observers ({@code
 * CiDaemonLauncher.BOOT_REAP_PRIORITY}, then {@code CiRunService.BOOT_SWEEP_PRIORITY}) so the run
 * table it reads is the one the sweep left, and it hands the work to a thread of its own rather than
 * blocking the startup thread on the network: that is {@code DaemonReleaseListener}'s lesson, paid
 * live — a startup observer that waits on a service loses the container healthcheck's race and cd
 * kills the deployment.
 */
@ApplicationScoped
public class CommissionReconciler {

  private static final Logger LOG = Logger.getLogger(CommissionReconciler.class);

  /**
   * Boot order, third. Both halves of the existing reconciliation run first — the container reap at
   * 2000 and the run sweep at 2100 — because what is reaped here is decided by which runs are still
   * {@code QUEUED} or {@code RUNNING}, and the sweep is what settles that.
   */
  public static final int BOOT_RECONCILE_PRIORITY = 2200;

  @Inject IdpCommissioner idp;

  @Inject RunCommissions commissions;

  @Inject CiRunRepository runs;

  /**
   * Skipped under {@code TEST}, like both boot observers it follows: the suites reach no idp by
   * intent. {@link #reconcile()} is what a test drives instead.
   */
  void onStart(@Observes @Priority(BOOT_RECONCILE_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST || !idp.enabled()) {
      return;
    }
    Thread sweep = new Thread(this::reconcile, "ci-commission-reconcile");
    sweep.setDaemon(true);
    sweep.start();
  }

  /**
   * The slow schedule underneath the boot pass. Hourly by default: what it collects is a leak of one
   * credential per process death, so a tighter interval would ask qits-idp for a listing far more
   * often than anything changes.
   */
  @Scheduled(
      every = "{qits.ci.commission.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void tick() {
    if (!idp.enabled()) {
      return;
    }
    reconcile();
  }

  /**
   * Read the live commissions, delete the ones no run owns. Package-private because both callers
   * above skip in a suite, so this is what a test calls directly.
   */
  void reconcile() {
    Optional<List<IdpCommissioner.LiveClient>> live = idp.live();
    if (live.isEmpty()) {
      return;
    }
    reap(live.get(), activeRunIds());
  }

  /** The run ids that still own a credential — see the class javadoc for why they are read here. */
  private Set<String> activeRunIds() {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                Set<String> ids = new HashSet<>();
                for (CiRun run : runs.listActiveNewestFirst()) {
                  ids.add(run.id);
                }
                return ids;
              });
    } catch (RuntimeException e) {
      // Nothing was learned about which runs are live, so nothing may be reaped: an empty set here
      // would read as "no run owns anything" and take every live credential with it.
      LOG.warnf("Could not read the active runs, so no commissioned credential is reaped: %s", e.toString());
      return null;
    }
  }

  /** The reaping itself, over an already-read listing — the seam a test drives with a set. */
  int reap(List<IdpCommissioner.LiveClient> live, Set<String> activeRunIds) {
    if (activeRunIds == null) {
      return 0;
    }
    int reaped = 0;
    for (IdpCommissioner.LiveClient each : live) {
      if (!IdpCommissioner.CONTEXT_KIND.equals(each.contextKind())) {
        continue;
      }
      if (activeRunIds.contains(each.contextId()) || commissions.holds(each.clientId())) {
        continue;
      }
      LOG.infof(
          "Reaping the commissioned credential %s of run %s, which is no longer running",
          each.clientId(), each.contextId());
      idp.decommission(each.clientId());
      reaped++;
    }
    if (reaped > 0) {
      LOG.infof("Reaped %d commissioned credential(s) no CI run owns any more", reaped);
    }
    return reaped;
  }
}
