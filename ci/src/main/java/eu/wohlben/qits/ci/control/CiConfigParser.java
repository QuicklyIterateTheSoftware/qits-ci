package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses the repo-committed {@code .config/qits/ci-post-receive.yml} (the {@code QitsConfigParser}
 * pattern: SnakeYAML {@link SafeConstructor}, plain maps/lists only — never instantiating classes
 * from repository content). Parsing is deliberately <b>lenient</b>: unknown keys, top-level or
 * per-step, are simply never read, so a repo can carry config for a newer qits-ci without breaking
 * on an older one. Only unparseable YAML, a structurally wrong {@code steps}, or a step missing
 * {@code script}/{@code image} is a config error ({@link CiConfigException} — recorded as a {@code
 * CONFIG_ERROR} run so a broken gate is visible rather than silently green).
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
    if (content == null || content.isBlank()) {
      return new CiPipeline(List.of());
    }
    Object root;
    try {
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      root = yaml.load(content);
    } catch (Exception e) {
      throw new CiConfigException("Invalid YAML: " + e.getMessage(), e);
    }
    if (root == null) {
      return new CiPipeline(List.of());
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw new CiConfigException("Expected a mapping at the document root, got: " + typeOf(root));
    }
    Object rawSteps = map.get("steps");
    if (rawSteps == null) {
      return new CiPipeline(List.of());
    }
    if (!(rawSteps instanceof List<?> list)) {
      throw new CiConfigException("Expected 'steps' to be a list, got: " + typeOf(rawSteps));
    }
    List<CiStepDecl> steps = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      Object entry = list.get(i);
      if (!(entry instanceof Map<?, ?> step)) {
        throw new CiConfigException("Step " + i + ": expected a mapping, got: " + typeOf(entry));
      }
      steps.add(new CiStepDecl(requireString(step, "image", i), requireString(step, "script", i)));
    }
    return new CiPipeline(List.copyOf(steps));
  }

  private static String requireString(Map<?, ?> step, String key, int index) {
    Object value = step.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new CiConfigException("Step " + index + ": missing required '" + key + "'");
    }
    return s;
  }

  private static String typeOf(Object value) {
    return value == null ? "null" : value.getClass().getSimpleName();
  }
}
