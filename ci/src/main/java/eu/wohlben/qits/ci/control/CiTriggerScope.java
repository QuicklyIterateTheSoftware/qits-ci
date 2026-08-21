package eu.wohlben.qits.ci.control;

/**
 * Which set of trigger files a listing reads, and the whole difference between the two kinds of
 * event pipeline.
 *
 * <p>Both live in {@link CiEventTriggerParser#CONFIG_DIR}, both parse with the same parser and are
 * selected with the same {@code event:}/{@code when:} grammar, and the run they record is the same
 * row. What differs is <b>whose file it is and which repository the run is about</b>:
 *
 * <ul>
 *   <li>{@link #REPOSITORY} — {@code .config/qits/ci-event-*.yml}, committed by the repository that
 *       is built. The run is recorded against the repository the file came from.
 *   <li>{@link #PLATFORM} — {@code .config/qits/ci-platform-event-*.yml}, committed once in the
 *       repository named by {@code qits.ci.platform-pipelines-repository}. The run is recorded
 *       against the repository the arriving event's payload names, so one file serves the whole
 *       catalogue.
 * </ul>
 *
 * <p>The two prefixes are disjoint by construction — {@code ci-platform-event-} does not start with
 * {@code ci-event-} — so a file belongs to exactly one scope and a listing of the directory can sort
 * them without a second read.
 */
public enum CiTriggerScope {

  /** The repository's own event triggers. */
  REPOSITORY(CiEventTriggerParser.CONFIG_PREFIX),

  /** The platform-wide event triggers, read from one configured repository. */
  PLATFORM(CiEventTriggerParser.PLATFORM_CONFIG_PREFIX);

  private final String prefix;

  CiTriggerScope(String prefix) {
    this.prefix = prefix;
  }

  /** The path prefix that makes a file in {@link CiEventTriggerParser#CONFIG_DIR} this scope's. */
  public String prefix() {
    return prefix;
  }

  /**
   * Whether a path listed in another repository's tree is one of this scope's trigger files. The
   * name is bounded to a plain slug for the reason {@link CiEventTriggerParser#isTriggerPath} gives:
   * it comes back from the git host and goes straight into a URL.
   */
  public boolean matches(String path) {
    return CiEventTriggerParser.isTriggerPath(path, prefix);
  }
}
