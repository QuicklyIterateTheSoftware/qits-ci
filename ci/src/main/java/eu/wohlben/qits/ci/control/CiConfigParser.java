package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;

/**
 * Parses the repo-committed {@code .config/qits/ci-post-receive.yml} (the {@code QitsConfigParser}
 * pattern: SnakeYAML {@code SafeConstructor}, plain maps/lists only — never instantiating classes
 * from repository content). Parsing is deliberately <b>lenient</b>: unknown keys, top-level or
 * per-step, are simply never read, so a repo can carry config for a newer qits-ci without breaking
 * on an older one. Only unparseable YAML, a structurally wrong {@code steps}, a step missing {@code
 * script}/{@code image}, or a key this parser <em>does</em> know carrying a value it cannot read
 * ({@code timeout-seconds}, {@code docker}) is a config error ({@link CiConfigException} — recorded
 * as a {@code CONFIG_ERROR} run so a broken gate is visible rather than silently green).
 *
 * <p><b>{@code event:}, {@code when:} and {@code artifacts:} are the one exception to that leniency,
 * and they are the two-way rule.</b> They belong to the <em>other</em> trigger type — {@code
 * .config/qits/ci-event-*.yml}, read by {@link CiEventTriggerParser} — and a repository that writes a
 * selection into this file has declared a trigger that will never fire. Ignoring them as "keys for a
 * newer qits-ci" would be the worst available answer: silent, permanent, and indistinguishable from
 * a trigger that simply never matched. So they are a config error here, exactly as {@code event:}'s
 * absence is a config error there. The two trigger types never blur.
 *
 * <p>{@code artifacts:} joined that list for its own reason rather than by symmetry. What a
 * declaration produces is a {@code SoftwareRelease} carrying the <b>triggering event's</b> version,
 * and a push carries no version at all — so the key could only ever be inert here, which is exactly
 * the allow-but-inert trap {@code branches:} is refused for on the other side.
 */
@ApplicationScoped
public class CiConfigParser {

  /** The committed file this domain reads, named after the git server-side hook event. */
  public static final String CONFIG_PATH = ".config/qits/ci-post-receive.yml";

  /** A structural problem in the config — surfaced as a {@code CONFIG_ERROR} run. */
  public static class CiConfigException extends RuntimeException {
    public CiConfigException(String message) {
      super(message);
    }

    public CiConfigException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Parses the YAML content into the step list. Blank content, an empty document, or an
   * empty/absent {@code steps} key yields an empty pipeline (recorded as a trivially green run —
   * the opt-in file is visible, unlike an absent file which records nothing).
   */
  public CiPipeline parse(String content) {
    Map<?, ?> root = CiConfigSchema.load(content, false);
    if (root == null) {
      return new CiPipeline(List.of());
    }
    rejectEventTriggerKeys(root);
    return CiConfigSchema.steps(root);
  }

  /** The half of the two-way rule this file owns; see the class javadoc for why it is loud. */
  private static void rejectEventTriggerKeys(Map<?, ?> root) {
    for (String key :
        List.of(
            CiConfigSchema.EVENT_KEY, CiConfigSchema.WHEN_KEY, CiConfigSchema.ARTIFACTS_KEY)) {
      if (root.containsKey(key)) {
        throw new CiConfigException(
            "'"
                + key
                + "' belongs to an event trigger and is not read from "
                + CONFIG_PATH
                + " — move it to .config/qits/ci-event-<name>.yml");
      }
    }
  }
}
