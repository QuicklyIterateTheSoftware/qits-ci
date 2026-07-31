package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * Where the pipeline config for a pushed commit comes from. The real implementation ({@link
 * GitConfigFetcher}) shells ci's own {@code git} against the git host's smart-HTTP URL; tests
 * replace it with an in-memory fake ({@code @io.quarkus.test.Mock}).
 */
public interface CiConfigSource {

  /**
   * Outcome of a config lookup. {@code content} is non-null only for {@link Status#FOUND}; {@code
   * message} carries the reason for {@link Status#INVALID}.
   */
  record ConfigLookup(Status status, String content, String message) {

    public enum Status {
      /** The pushed commit carries the config file. */
      FOUND,
      /** The pushed commit has no config file — the repo has not opted in for this push. */
      ABSENT,
      /**
       * The commit is no longer reachable in the repository (amended/force-pushed away before the
       * run started). Nothing is recorded — the push it belonged to no longer exists, so a red run
       * would blame a commit whose build was never broken.
       */
      GONE,
      /** The git host could not be reached at all — nothing is recorded. */
      UNREACHABLE,
      /** The file exists but cannot be a valid config (e.g. absurdly large) ⇒ CONFIG_ERROR. */
      INVALID
    }

    public static ConfigLookup found(String content) {
      return new ConfigLookup(Status.FOUND, content, null);
    }

    public static ConfigLookup absent() {
      return new ConfigLookup(Status.ABSENT, null, null);
    }

    public static ConfigLookup gone() {
      return new ConfigLookup(Status.GONE, null, null);
    }

    public static ConfigLookup unreachable() {
      return new ConfigLookup(Status.UNREACHABLE, null, null);
    }

    public static ConfigLookup invalid(String message) {
      return new ConfigLookup(Status.INVALID, null, message);
    }
  }

  /**
   * Every {@code .config/qits/ci-event-*.yml} at a branch's current head, with the head it read
   * them at. Empty {@code files} on a {@link Status#FOUND} is the ordinary case — most repositories
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
   * Reads {@link CiConfigParser#CONFIG_PATH} from {@code sha}, which must still be reachable from
   * {@code branch} in the repository.
   */
  ConfigLookup read(String repoId, String branch, String sha);

  /**
   * Lists and reads the repository's event-trigger files at {@code branch}'s current head.
   *
   * <p>This is the <b>event</b> half of the same seam {@link #read} is the push half of, and the
   * difference in shape is the difference between the two triggers: a push names its own commit,
   * while an event names none, so the platform's one tracked branch supplies it (every submodule
   * follows {@code main}) and the head is resolved rather than given.
   */
  EventTriggerLookup readEventTriggers(String repoId, String branch);
}
