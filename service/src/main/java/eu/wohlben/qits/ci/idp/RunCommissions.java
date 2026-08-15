package eu.wohlben.qits.ci.idp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * One commissioned credential per run, for as long as the run lasts.
 *
 * <p><b>Commissioned lazily, at the first step.</b> Every step first clones its repository from the
 * authenticated git host, so every step needs the pair. The Git credential helper exchanges it for
 * a short-lived githost bearer rather than ever putting the pair on the wire. Every later step of
 * the same run reuses it: the credential belongs to the run rather than to the step, and one
 * commission per step would be N clients to leak instead of one.
 *
 * <p><b>Given back at {@code runClosed}.</b> {@code CiDaemonStepRunner.runClosed} is called from a
 * {@code finally} on both run bodies, which is what makes the release unconditional. The paths that
 * never enter a run body — a supersede at accept, a {@code QUEUED} cancel, a row the boot sweep
 * failed — commissioned nothing in this process and have nothing to give back; what covers a
 * credential this process died holding is {@link CommissionReconciler}.
 *
 * <p><b>Memory, not a row.</b> A commission is worth exactly one run and a run does not survive this
 * process: a restart fails or re-enqueues every run it was holding, so a persisted pair would name a
 * credential no run will ever present. The reconciliation is the durable half, and it needs no
 * table of ours because qits-idp already holds the list.
 */
@ApplicationScoped
public class RunCommissions {

  private static final Logger LOG = Logger.getLogger(RunCommissions.class);

  @Inject IdpCommissioner idp;

  /** One entry per run that has reached its first step, removed when the run closes. */
  private final Map<String, IdpCommissioner.Commission> byRun = new ConcurrentHashMap<>();

  /**
   * This run's credential, commissioned on the first ask, or {@code null} when there is nothing to
   * commission with — see {@link IdpCommissioner#enabled()}, the arm on which a step container's
   * environment stays byte-identical to what it was before per-run credentials existed.
   *
   * <p>The null tolerance on the collaborator is the launcher's own: the hand-wired launchers in the
   * ITs set the fields a case needs and leave the rest, so an unset one is a test's silence rather
   * than a wiring failure.
   *
   * @throws IdpCommissioner.CommissionFailedException when qits-idp could not be asked, which fails
   *     the step rather than launching it credential-less
   */
  public IdpCommissioner.Commission forRun(String runId) {
    if (idp == null || !idp.enabled()) {
      return null;
    }
    IdpCommissioner.Commission held = byRun.get(runId);
    if (held != null) {
      return held;
    }
    IdpCommissioner.Commission fresh = idp.commission(IdpCommissioner.CONTEXT_KIND, runId);
    byRun.put(runId, fresh);
    return fresh;
  }

  /**
   * Give this run's credential back, if it had one. <b>Never throws</b>: the caller is a run that is
   * already over, and a failure here costs a reconciliation rather than a run.
   */
  public void release(String runId) {
    IdpCommissioner.Commission gone = byRun.remove(runId);
    if (gone == null || idp == null) {
      return;
    }
    try {
      idp.decommission(gone.clientId());
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not decommission run %s's credential %s: %s — leaving it to the next"
              + " reconciliation",
          runId, gone.clientId(), e.toString());
    }
  }

  /**
   * Whether this process is currently holding that commissioned client — what keeps {@link
   * CommissionReconciler} off a credential a run is using right now, in the window between a run's
   * row going terminal and its {@code runClosed}.
   */
  public boolean holds(String commissionedClientId) {
    for (IdpCommissioner.Commission each : byRun.values()) {
      if (each.clientId().equals(commissionedClientId)) {
        return true;
      }
    }
    return false;
  }
}
