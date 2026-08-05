package eu.wohlben.qits.ci.control;

import java.util.Set;

/**
 * "Every git repository the host has" — the git host's own listing, read once per arriving event by
 * {@link ListedAndKnownCiRepos}.
 *
 * <p>An interface rather than a call so {@code ci} stays free of {@code java.net.http} and of the
 * git host's wire shape — the same reason {@link CdNotifier} and {@link DaemonReleaseLog} are ports
 * here and hand-rolled clients in {@code service}. <b>Zero implementations is a supported
 * configuration</b>, the same precedent: {@link ListedAndKnownCiRepos} injects an {@code Instance}
 * and a deployment with no implementation simply answers from what qits-ci already knows, which is
 * exactly what this platform did before the listing existed.
 */
public interface GitHostRepoListing {

  /**
   * Every repository id the git host holds, or an <b>empty set</b> when the listing could not be
   * read. <b>Never throws</b>: this runs on {@code ci-trigger-worker} in front of the evaluation, and
   * a read failure must not shrink the candidate set below what qits-ci already knows, let alone
   * abort the evaluation. An implementation logs its own WARN for the failure and answers empty.
   *
   * <p>Every id it returns reaches a {@code git} argv through {@link CiConfigSource}, so an
   * implementation answers only ids {@link CiIdentifiers} accepts — it is reading another service's
   * bytes, and the id is the part of them that travels.
   */
  Set<String> repositories();
}
