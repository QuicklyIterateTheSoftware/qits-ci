package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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

  /** Appends a lookup: the first {@code put} answers the first read, the second the next, … */
  public void put(String repoId, String sha, ConfigLookup lookup) {
    byCommit.computeIfAbsent(repoId + "@" + sha, k -> new ArrayDeque<>()).add(lookup);
  }

  public void reset() {
    byCommit.clear();
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
}
