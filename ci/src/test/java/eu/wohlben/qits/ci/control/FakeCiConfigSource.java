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
 * The pipeline config for the ci suite: an in-memory map the tests populate per (repoId, sha).
 * Unknown commits read as {@link ConfigLookup#absent()}. The production implementation is {@code
 * service/…/githost/HttpGitConfigSource}, which this module ships none of.
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
   * The event half, per (repoId, branch, scope). Unseeded repositories answer {@link
   * EventTriggerLookup#found} with no files — "this repository declares no trigger", which is the
   * ordinary case and not an error.
   *
   * <p>The scope is part of the key because a platform read and a repository read of the same
   * repository answer different files, which is the whole of what the scope means.
   */
  private final Map<String, EventTriggerLookup> triggersByBranch = new HashMap<>();

  /** Every config {@code read} this fake was asked, in order — the requeue bound's own assertion. */
  private final List<String> configReads = Collections.synchronizedList(new ArrayList<>());

  /** Every {@code readEventTriggers} this fake was asked, in order — the listing's own assertion. */
  private final List<String> triggerReads = Collections.synchronizedList(new ArrayList<>());

  private final Map<String, Runnable> duringReads = new HashMap<>();

  /**
   * Every reference this fake was addressed with, in order — how a test says whether a read went out
   * name-addressed or id-addressed without standing up an HTTP server for it. The url shapes
   * themselves are {@code HttpGitConfigSourceTest}'s.
   */
  private final List<CiRepoRef> addressed = Collections.synchronizedList(new ArrayList<>());

  /** Appends a lookup: the first {@code put} answers the first read, the second the next, … */
  public void put(String repoId, String sha, ConfigLookup lookup) {
    byCommit.computeIfAbsent(repoId + "@" + sha, k -> new ArrayDeque<>()).add(lookup);
  }

  /**
   * Run something on the worker thread <b>while</b> this commit's config is being read, once — the
   * read-path analogue of {@link FakeCiStepRunner#during}. It is how a test stages what only exists
   * mid-read (a cancellation arriving while the row is {@code RUNNING} inside the fetch) without a
   * sleep and without a race.
   */
  public void duringRead(String repoId, String sha, Runnable action) {
    duringReads.put(repoId + "@" + sha, action);
  }

  /** Seeds the trigger files a repository's branch head carries. */
  public void putTriggers(String repoId, String branch, String headSha, EventTriggerFile... files) {
    putTriggers(repoId, branch, CiTriggerScope.REPOSITORY, headSha, files);
  }

  /** The same, for one scope — how a test seeds the platform-pipelines repository's own files. */
  public void putTriggers(
      String repoId,
      String branch,
      CiTriggerScope scope,
      String headSha,
      EventTriggerFile... files) {
    triggersByBranch.put(key(repoId, branch, scope), EventTriggerLookup.found(headSha, List.of(files)));
  }

  /** Seeds a repository whose branch cannot be read at all — deleted, or the git host is down. */
  public void putTriggersUnreachable(String repoId, String branch) {
    putTriggersUnreachable(repoId, branch, CiTriggerScope.REPOSITORY);
  }

  /** The same, for one scope. */
  public void putTriggersUnreachable(String repoId, String branch, CiTriggerScope scope) {
    triggersByBranch.put(key(repoId, branch, scope), EventTriggerLookup.unreachable());
  }

  private static String key(String repoId, String branch, CiTriggerScope scope) {
    return repoId + "@" + branch + "#" + scope;
  }

  public List<String> configReads() {
    return List.copyOf(configReads);
  }

  public List<String> triggerReads() {
    return List.copyOf(triggerReads);
  }

  public List<CiRepoRef> addressed() {
    return List.copyOf(addressed);
  }

  public void reset() {
    byCommit.clear();
    triggersByBranch.clear();
    configReads.clear();
    triggerReads.clear();
    addressed.clear();
    duringReads.clear();
  }

  @Override
  public ConfigLookup read(CiRepoRef repo, String branch, String sha) {
    String repoId = repo.repoId();
    addressed.add(repo);
    configReads.add(repoId + "@" + sha);
    Runnable midRead = duringReads.remove(repoId + "@" + sha);
    if (midRead != null) {
      midRead.run();
    }
    Deque<ConfigLookup> queued = byCommit.get(repoId + "@" + sha);
    if (queued == null || queued.isEmpty()) {
      return ConfigLookup.absent();
    }
    // Keep the last value standing so repeated reads stay answerable.
    return queued.size() == 1 ? queued.peek() : queued.poll();
  }

  @Override
  public EventTriggerLookup readEventTriggers(
      CiRepoRef repo, String branch, CiTriggerScope scope) {
    String repoId = repo.repoId();
    addressed.add(repo);
    triggerReads.add(key(repoId, branch, scope));
    EventTriggerLookup seeded = triggersByBranch.get(key(repoId, branch, scope));
    return seeded == null ? EventTriggerLookup.found("0".repeat(40), List.of()) : seeded;
  }
}
