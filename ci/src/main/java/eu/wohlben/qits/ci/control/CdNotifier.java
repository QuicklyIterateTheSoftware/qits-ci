package eu.wohlben.qits.ci.control;

/**
 * The port {@link CiRunService} announces a green run through — the seam qits-cd hangs off. Ids and
 * names only, never entities; called once per run, after the run's terminal row is committed, and
 * only for {@code SUCCESS} (a red run, a {@code CONFIG_ERROR} and a discarded run deploy nothing).
 *
 * <p>An interface rather than a call so this module stays free of any web stack: the sole
 * production implementation is {@code service/…/notify/CdBuildNotifier}, a fire-and-forget HTTP
 * POST behind {@code qits.cd.intake-url}. It is resolved via {@code Instance} and absent is a
 * supported configuration — a deployment without qits-cd runs CI exactly as before.
 *
 * <p><b>An implementation must never block the caller.</b> It runs on the single-threaded run
 * worker, between one run and the next; anything slower than queueing an async send delays every
 * pipeline on the instance (the no-untimed-wait rule, one package over).
 */
public interface CdNotifier {

  void onRunSucceeded(String runId, String repoId, String branch, String commitSha);
}
