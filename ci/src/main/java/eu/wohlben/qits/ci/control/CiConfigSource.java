package eu.wohlben.qits.ci.control;

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
   * Reads {@link CiConfigParser#CONFIG_PATH} from {@code sha}, which must still be reachable from
   * {@code branch} in the repository.
   */
  ConfigLookup read(String repoId, String branch, String sha);
}
