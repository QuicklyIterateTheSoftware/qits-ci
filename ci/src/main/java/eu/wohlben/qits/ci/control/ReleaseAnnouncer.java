package eu.wohlben.qits.ci.control;

import java.time.Instant;

/**
 * The port {@link CiRunService} announces a <b>published artifact</b> through — the second seam the
 * event bus hangs off, beside {@link RunAnnouncer} and deliberately not the same one.
 *
 * <p>The two are separate because they say different things about the same green run.
 * {@code RunAnnouncer} says <em>a build passed</em>, once, for every green run there has ever been.
 * This one says <em>this exact package is in qits-artifacts and you can install it</em>, once per
 * artifact the trigger file declared, and only for a release pipeline. Folding them into one port
 * would put "the run finished" and "the registry has it" behind one name, which is precisely the
 * conflation the whole redesign exists to undo: the previous single event fired at release-push time
 * and was read as "the package exists", and the gap between those two moments is an upstream build.
 *
 * <p><b>Fan-out is the caller's, and the port takes one artifact.</b> N declarations are N calls, so
 * a partial failure costs one announcement rather than the rest, and nothing here has to know that
 * the events are siblings. The bus side already supports the shape — the outbox enqueues one row per
 * event in its own transaction and {@code CausationScope.current()} is a non-consuming read.
 *
 * <p>An interface rather than a call so this module stays free of the bus and its transport; the
 * sole production implementation is {@code service/…/bus/SoftwareReleaseAnnouncer}, and zero
 * implementations is a supported configuration. <b>The same must-not-block rule as
 * {@link RunAnnouncer}</b>, with the same reason and one sharper edge: this runs on the
 * single-threaded run worker and a release pipeline may declare several artifacts, so an unreachable
 * qits-events costs the publish timeout <em>per artifact</em>.
 */
public interface ReleaseAnnouncer {

  /**
   * A release pipeline went green and this artifact is published.
   *
   * @param runId the run that published it. <b>Not on the wire</b> — the event names the artifact,
   *     and a consumer installing a package has no business with a CI run id — but an announcement
   *     that goes wrong has to be traceable to the run it came from.
   * @param repoId the repository whose pipeline published it — this repo, not the upstream that
   *     triggered it
   * @param version the version, read out of the triggering event's payload. qits-ci publishes
   *     nothing when that field is absent: the declaration was written for a trigger that cannot
   *     feed it.
   * @param packageType {@code npm} or {@code docker} — {@link CiArtifact.Type#declared()}, the
   *     keyword the trigger file used, which is also the wire value
   * @param packageName the exact package name, unqualified for docker ({@code qits/qits-stt}): no
   *     registry-qualified reference is portable across the step network and this process's own
   * @param finishedAt the run's terminal timestamp, which is when the artifact became available
   * @param triggerEventId the event that caused this run, stamped as the published event's parent —
   *     a plain String for the reason {@link RunAnnouncer} argues in full. Never null in practice:
   *     only an event-triggered run can carry a declaration.
   */
  void onArtifactPublished(
      String runId,
      String repoId,
      String version,
      String packageType,
      String packageName,
      Instant finishedAt,
      String triggerEventId);
}
