package eu.wohlben.qits.ci.control;

import java.time.Instant;
import java.util.List;

/**
 * "The last N daemon releases, newest first" -- the events-API read port
 * (ci-daemon-autoadopt-plan.md §1.6, §2.8). A future startup reconciliation calls it to seed the
 * ladder with what a restart missed, the same way a live {@code SoftwareRelease} does.
 *
 * <p>An interface rather than a call so {@code ci} stays free of {@code java.net.http} and the
 * qits-events wire shape -- the same reason {@link PdNotifier} is a port here and a hand-rolled
 * client in {@code service}. <b>Zero implementations is a supported configuration</b>, the {@link
 * PdNotifier} precedent: this port lands before its sole production implementation does ({@code
 * service/…/bus/EventsDaemonReleaseLog}), and a deployment with no implementation simply never
 * reconciles at startup -- the ladder is whatever the durable table already holds.
 */
public interface DaemonReleaseLog {

  /** One {@code SoftwareRelease} for the daemon, as read off the event log -- the same three fields
   *  {@link CiDaemonPins#adopt} takes. */
  record Release(String version, String eventId, Instant occurredAt) {}

  /**
   * The newest {@code limit} releases, newest first, or an empty list when qits-events could not be
   * reached. <b>Never throws for an unreachable log</b> -- an implementation answers empty and logs
   * its own WARN, because an unreachable qits-events must leave the ladder exactly as it was, not
   * empty it.
   */
  List<Release> recentReleases(int limit);
}
