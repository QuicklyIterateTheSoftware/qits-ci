package eu.wohlben.qits.ci.control;

import java.time.Instant;

/**
 * The port {@link CiRunService} announces a green run to the <b>platform at large</b> through — the
 * seam the event bus hangs off, and <b>the only announcement a green run makes</b>.
 *
 * <p>It used to be one of two, and the sibling is worth knowing about because the shape it left is
 * the shape of this one. {@code PdNotifier} was a <em>request</em> addressed to one named service:
 * qits-platform-deployments was asked to deploy, over HTTP, at a URL this repo configured, on every
 * green run. This is a <em>statement</em> addressed to nobody in particular — "a build passed" —
 * which qits-events records and anything on the platform may subscribe to, this service included.
 * The deployer is one of those subscribers now: it consumes {@code BuildSuccessful} durably and
 * calls its own announce path, so a disconnect delays a deployment instead of losing it, which is
 * what a fire-and-forget POST could never offer. Its HTTP intake stays as the manual and recovery
 * door; qits-ci is simply no longer one of the callers.
 *
 * <p>The signature carries {@code finishedAt} because an event carries <b>when it happened</b> —
 * that is what an event log is for — and the value is the run's own terminal timestamp rather than
 * the moment the announcement was made. The two differ by however long the transition took, and it
 * is never null: the wire contract makes {@code occurredAt} mandatory.
 *
 * <p>An interface rather than a call so this module stays free of the bus and its transport: the
 * sole production implementation is {@code service/…/bus/BuildSuccessfulAnnouncer}. It is resolved
 * via {@code Instance} and absent is a supported configuration — a deployment with no qits-events
 * runs CI exactly as before, and announces nothing at all.
 *
 * <p><b>An implementation must not block the caller</b>, with one teeth-gritting caveat: this runs
 * on a run worker, between one run and the next.
 * The bus implementation's {@code publish()} is synchronous and never throws, but it is not free —
 * it is bounded by the publish timeout when qits-events is unreachable, after which the outbox owns
 * the event. A few seconds per green build, paid only while the far side is down, is the price that
 * was accepted for it; anything slower than that does not belong behind this port.
 */
public interface RunAnnouncer {

  /**
   * A run went green. {@code triggerEventId} is <b>the event that caused this run</b>, or null when
   * nothing did — which is every push, and a push publishing a chain root is correct.
   *
   * <p>{@code repoId} is the storage id and is always set; {@code projectId} and {@code repoName}
   * are the public {@code (project, name)} pair off the run's own row, present when the announcing
   * push arrived name-addressed and null when it did not. They ride the event so the deployer names
   * the image {@code qits/<repoName>:<sha>} instead of falling back to the id.
   *
   * <p><b>A plain {@code String}, and that is the whole reason this parameter is here rather than an
   * ambient value.</b> It is a foreign id, which is exactly how this module names foreign things, and
   * it keeps {@code ci/} free of every eventstream type — the dependency this seam exists to
   * prevent. The bus stamps causation from a thread-local that the implementation could have read
   * instead; it would read null, because the engine consumed the frame on the socket's dispatch
   * thread and this call happens later on {@code ci-run-worker}. A thread-local does not follow work,
   * deliberately. So the id travels durably on {@code CiRun.triggerEventId} and arrives here as an
   * argument, and {@code BuildSuccessfulAnnouncer} hands it to {@code publish(event, parent)} — where
   * an explicit non-null argument outranks the ambient context by design, precisely for this case.
   */
  void onRunSucceeded(
      String runId,
      String repoId,
      String projectId,
      String repoName,
      String branch,
      String commitSha,
      Instant finishedAt,
      String triggerEventId);
}
