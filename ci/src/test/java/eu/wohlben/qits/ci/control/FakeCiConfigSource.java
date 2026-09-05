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
 * The {@link CiConfigSource} for the ci suite: an in-memory catalogue of trigger files per (repoId,
 * branch, scope), plus per-commit answers to the commit-held probe.
 *
 * <p><b>It used to carry a third thing and the push arm took it with it.</b> {@code read(repo,
 * branch, sha)} answered a whole {@code ConfigLookup} — FOUND with content, ABSENT, GONE,
 * UNREACHABLE, INVALID — because the push path read a pipeline out of a commit on the run worker.
 * Nothing reads a pipeline out of a commit any more: a trigger file is read at a branch head by
 * {@code CiEventTriggerService}, and the only per-commit question left is whether the repository
 * still holds the sha a step container failed to check out.
 *
 * <p>So a commit is {@link CommitHeld#HELD} unless a test says otherwise, which is the interesting
 * default rather than a neutral one: it is what a healthy git host answers, and staging a discard
 * therefore has to be deliberate.
 */
@Mock
@ApplicationScoped
public class FakeCiConfigSource implements CiConfigSource {

  /**
   * Queued answers per commit, so a test can model a repository that changed between two probes.
   * The last value stands for every further read.
   */
  private final Map<String, Deque<CommitHeld>> byCommit = new HashMap<>();

  /**
   * The event half, per (repoId, branch, scope). Unseeded repositories answer {@link
   * EventTriggerLookup#found} with no files — "this repository declares no trigger", which is the
   * ordinary case and not an error.
   *
   * <p>The scope is part of the key because a platform read and a repository read of the same
   * repository answer different files, which is the whole of what the scope means.
   */
  private final Map<String, EventTriggerLookup> triggersByBranch = new HashMap<>();

  /** Every commit-held probe this fake was asked, in order. */
  private final List<String> commitProbes = Collections.synchronizedList(new ArrayList<>());

  /** Every {@code readEventTriggers} this fake was asked, in order — the listing's own assertion. */
  private final List<String> triggerReads = Collections.synchronizedList(new ArrayList<>());

  /**
   * Every reference this fake was addressed with, in order — how a test says whether a read went out
   * name-addressed or id-addressed without standing up an HTTP server for it. The url shapes
   * themselves are {@code HttpGitConfigSourceTest}'s.
   */
  private final List<CiRepoRef> addressed = Collections.synchronizedList(new ArrayList<>());

  /**
   * Appends an answer to the commit-held probe: the first {@code put} answers the first probe, the
   * second the next, … An unseeded commit is {@link CommitHeld#HELD}.
   */
  public void putCommit(String repoId, String sha, CommitHeld held) {
    byCommit.computeIfAbsent(repoId + "@" + sha, k -> new ArrayDeque<>()).add(held);
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

  public List<String> commitProbes() {
    return List.copyOf(commitProbes);
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
    commitProbes.clear();
    triggerReads.clear();
    addressed.clear();
  }

  @Override
  public CommitHeld commitHeld(CiRepoRef repo, String sha) {
    String repoId = repo.repoId();
    addressed.add(repo);
    commitProbes.add(repoId + "@" + sha);
    Deque<CommitHeld> queued = byCommit.get(repoId + "@" + sha);
    if (queued == null || queued.isEmpty()) {
      return CommitHeld.HELD;
    }
    // Keep the last value standing so repeated probes stay answerable.
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
