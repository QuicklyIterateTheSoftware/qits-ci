package eu.wohlben.qits.ci.control;

import java.time.Instant;

/**
 * The port {@link CiRunService} announces a green run to the <b>platform at large</b> through — the
 * seam the event bus hangs off, sibling to {@link CdNotifier} and deliberately not the same one.
 *
 * <p>The two look alike and mean different things. {@code CdNotifier} is a <em>request</em>
 * addressed to one named service: qits-cd is asked to deploy, at a URL this repo configures, and if
 * nobody is listening nothing was supposed to happen. This is a <em>statement</em> addressed to
 * nobody in particular — "a build passed" — which qits-events records and anything on the platform
 * may subscribe to, this service included. Folding them into one port would put cd's intake URL and
 * the event log's retention policy behind the same name.
 *
 * <p>Hence the one difference in the signature: {@code finishedAt}. An announcement to a service
 * that is about to act carries only what it needs to act on; an event carries <b>when it happened</b>,
 * because that is what an event log is for, and the value is the run's own terminal timestamp rather
 * than the moment the announcement was made. The two differ by however long the transition took, and
 * it is never null — the wire contract makes {@code occurredAt} mandatory.
 *
 * <p>An interface rather than a call so this module stays free of the bus and its transport: the
 * sole production implementation is {@code service/…/bus/BuildSuccessfulAnnouncer}. It is resolved
 * via {@code Instance} and absent is a supported configuration — a deployment with no qits-events
 * runs CI exactly as before.
 *
 * <p><b>The same must-not-block rule as {@link CdNotifier}</b>, with the same reason and one extra
 * teeth-gritting caveat: this runs on the single-threaded run worker, between one run and the next.
 * The bus implementation's {@code publish()} is synchronous and never throws, but it is not free —
 * it is bounded by the publish timeout when qits-events is unreachable, after which the outbox owns
 * the event. A few seconds per green build, paid only while the far side is down, is the price that
 * was accepted for it; anything slower than that does not belong behind this port.
 */
public interface RunAnnouncer {

  void onRunSucceeded(
      String runId, String repoId, String branch, String commitSha, Instant finishedAt);
}
