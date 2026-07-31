package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces {@link GitConfigFetcher} for the ci suite: an in-memory map the tests populate per
 * (repoId, sha). Unknown commits read as {@link ConfigLookup#absent()}.
 *
 * <p>Lookups are a <b>queue</b> per commit, because the service legitimately reads twice: once to
 * find the config, and again after a failed workspace setup to ask whether the commit is still
 * reachable. Queue several values to model a repository that changed in between; the last value
 * stands for every further read.
 */
@Mock
@ApplicationScoped
public class FakeCiConfigSource implements CiConfigSource {

  private final Map<String, Deque<ConfigLookup>> byCommit = new HashMap<>();

  /**
   * The event half, per (repoId, branch). Unseeded repositories answer {@link
   * EventTriggerLookup#found} with no files — "this repository declares no trigger", which is the
   * ordinary case and not an error.
   */
  private final Map<String, EventTriggerLookup> triggersByBranch = new HashMap<>();

  /** Every {@code readEventTriggers} this fake was asked, in order — the listing's own assertion. */
  private final List<String> triggerReads = Collections.synchronizedList(new ArrayList<>());

  /** Appends a lookup: the first {@code put} answers the first read, the second the next, … */
  public void put(String repoId, String sha, ConfigLookup lookup) {
    byCommit.computeIfAbsent(repoId + "@" + sha, k -> new ArrayDeque<>()).add(lookup);
  }

  /** Seeds the trigger files a repository's branch head carries. */
  public void putTriggers(String repoId, String branch, String headSha, EventTriggerFile... files) {
    triggersByBranch.put(
        repoId + "@" + branch, EventTriggerLookup.found(headSha, List.of(files)));
  }

  /** Seeds a repository whose branch cannot be read at all — deleted, or the git host is down. */
  public void putTriggersUnreachable(String repoId, String branch) {
    triggersByBranch.put(repoId + "@" + branch, EventTriggerLookup.unreachable());
  }

  public List<String> triggerReads() {
    return List.copyOf(triggerReads);
  }

  public void reset() {
    byCommit.clear();
    triggersByBranch.clear();
    triggerReads.clear();
  }

  @Override
  public ConfigLookup read(String repoId, String branch, String sha) {
    Deque<ConfigLookup> queued = byCommit.get(repoId + "@" + sha);
    if (queued == null || queued.isEmpty()) {
      return ConfigLookup.absent();
    }
    // Keep the last value standing so repeated reads stay answerable.
    return queued.size() == 1 ? queued.peek() : queued.poll();
  }

  @Override
  public EventTriggerLookup readEventTriggers(String repoId, String branch) {
    triggerReads.add(repoId + "@" + branch);
    EventTriggerLookup seeded = triggersByBranch.get(repoId + "@" + branch);
    return seeded == null ? EventTriggerLookup.found("0".repeat(40), List.of()) : seeded;
  }
}
