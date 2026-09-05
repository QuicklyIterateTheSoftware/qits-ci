package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * Where a repository's committed pipeline files come from, and whether it still holds a commit. The
 * production implementation is {@code githost/HttpGitConfigSource} in the {@code service} module —
 * it reads both off the git host's content endpoints — and it is a port here for the reason {@link
 * GitHostRepoListing} is: {@code ci} stays free of {@code java.net.http}. Tests replace it with an
 * in-memory fake ({@code @io.quarkus.test.Mock}).
 *
 * <p><b>There used to be a third answer here and it retired with per-push CI (2026-09-05).</b>
 * {@code ConfigLookup read(repo, branch, sha)} fetched {@code .config/qits/ci-post-receive.yml} at a
 * pushed commit and answered FOUND/ABSENT/GONE/UNREACHABLE/INVALID — five outcomes that existed to
 * decide what a push run should record. No push becomes a run any more, so four of them have no
 * reader; what survived is the one question the <em>shared</em> run path still asks, and {@link
 * #commitHeld} is that question under its own name.
 */
public interface CiConfigSource {

  /**
   * Whether a repository still holds a commit. Three answers, and the third is not a weaker second:
   * a host that could not be asked has said nothing about the commit, and a caller that read it as
   * {@link #GONE} would discard a run over a network blip.
   */
  enum CommitHeld {
    /** The repository resolves the commit. */
    HELD,
    /**
     * The repository does not hold the commit at all — it was amended or force-pushed away and
     * garbage-collected.
     *
     * <p>It is <b>held</b> rather than <b>reachable</b>, and the difference is deliberate: a commit
     * the branch has moved past is still held, and a run that built it still says something true.
     */
    GONE,
    /** The git host could not be reached, or answered something that is not an answer. */
    UNKNOWN
  }

  /**
   * Every trigger file of one {@link CiTriggerScope} at a branch's current head, with the head it
   * read them at. Empty {@code files} on a {@link Status#FOUND} is the ordinary case — most repositories
   * declare no event trigger — and is not an error.
   *
   * <p>The head sha travels with the files because a run records the commit it built, and the two
   * must be the same read: resolving the head twice would let a push land in between and record a
   * run against a commit whose trigger file said something else.
   */
  record EventTriggerLookup(Status status, String headSha, List<EventTriggerFile> files) {

    public enum Status {
      /** The branch was resolved. {@code files} is what it carries, possibly nothing. */
      FOUND,
      /**
       * The repository or the branch could not be read — the git host is down, the repository was
       * deleted, or it has no such branch. Indistinguishable here on purpose: all three mean "no
       * trigger can be evaluated for this repository right now", and none of them is a run.
       */
      UNREACHABLE
    }

    public static EventTriggerLookup found(String headSha, List<EventTriggerFile> files) {
      return new EventTriggerLookup(Status.FOUND, headSha, List.copyOf(files));
    }

    public static EventTriggerLookup unreachable() {
      return new EventTriggerLookup(Status.UNREACHABLE, null, List.of());
    }
  }

  /** One trigger file: its path in the tree, which is identity, and its content. */
  record EventTriggerFile(String path, String content) {}

  /**
   * Does {@code repo} still hold {@code sha}? Asked in exactly one place — {@code
   * CiRunService.runSteps}, when a step container reports it could not check the commit out — and
   * the two causes it tells apart mean opposite things to the record. The commit force-pushed away
   * since the run was accepted describes a push that no longer exists, so the run is discarded; a
   * commit the repository still holds means the clone failed for some other reason, which is a real
   * failure that stays red.
   *
   * <p>{@link CommitHeld#UNKNOWN} is neither, and a caller must not collapse it into {@code GONE}:
   * discarding a run because the git host was briefly unreachable would erase a verdict about a
   * commit nobody has any evidence against.
   *
   * <p>The repository arrives as a {@link CiRepoRef} rather than an id because the git host serves
   * the same content name-addressed, and after the identity cutover the id route is qits-projects'
   * alone. An implementation reads name-addressed when the reference {@link CiRepoRef#named() is
   * named} and id-addressed when it is not.
   */
  CommitHeld commitHeld(CiRepoRef repo, String sha);

  /**
   * Lists and reads the repository's event-trigger files at {@code branch}'s current head.
   *
   * <p>This is the whole of what qits-ci reads a repository's config <em>for</em>: an event names no
   * commit of its own, so the platform's one tracked branch supplies it (every submodule follows
   * {@code main}) and the head is resolved rather than given.
   */
  default EventTriggerLookup readEventTriggers(CiRepoRef repo, String branch) {
    return readEventTriggers(repo, branch, CiTriggerScope.REPOSITORY);
  }

  /**
   * The same read, for a chosen scope: the repository's own {@code ci-event-*.yml} or the platform's
   * {@code ci-platform-event-*.yml}.
   *
   * <p><b>One scope per call, never both.</b> A platform read is a read of a <em>different</em>
   * repository — the one {@code qits.ci.platform-pipelines-repository} names — and a repository read
   * of that same repository still answers only its own files. Merging them would make one lookup
   * mean two things and would hide which file a run came from.
   */
  EventTriggerLookup readEventTriggers(CiRepoRef repo, String branch, CiTriggerScope scope);
}
