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
 * on an older one. Only unparseable YAML, a structurally wrong {@code steps}, a step missing {@code
 * script}/{@code image}, or a key this parser <em>does</em> know carrying a value it cannot read
 * ({@code timeout-seconds}, {@code docker}) is a config error ({@link CiConfigException} — recorded
 * as a {@code CONFIG_ERROR} run so a broken gate is visible rather than silently green).
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
      steps.add(
          new CiStepDecl(
              requireString(step, "image", i),
              requireString(step, "script", i),
              optionalTimeoutSeconds(step, i),
              optionalDocker(step, i)));
    }
    return new CiPipeline(List.copyOf(steps));
  }

  /**
   * The optional per-step {@code timeout-seconds}. Absent means the deployment's {@code
   * qits.ci.step-timeout-seconds}, i.e. exactly the behaviour before this key existed — the
   * leniency above is about keys this parser does not <em>know</em>, and this one it knows, so a
   * value that cannot be a deadline is a config error rather than something quietly ignored. A repo
   * that meant to bound a step and mistyped the number must find out.
   */
  private static Integer optionalTimeoutSeconds(Map<?, ?> step, int index) {
    Object value = step.get("timeout-seconds");
    if (value == null) {
      return null;
    }
    if (!(value instanceof Integer seconds) || seconds <= 0) {
      throw new CiConfigException(
          "Step " + index + ": 'timeout-seconds' must be a positive whole number of seconds");
    }
    return seconds;
  }

  /**
   * The optional per-step {@code docker} flag: whether the host mounts its docker socket into this
   * step's container, which is how a pipeline's last step runs {@code docker build && docker push}.
   * Absent means false, and false is the sandbox every step has had until now.
   *
   * <p>It is held to the same standard as {@code timeout-seconds} and for a sharper reason: a value
   * this parser knows and cannot read is a config error, never a quiet default. Declaring the flag
   * makes a step <b>root-equivalent on the host</b>, so {@code docker: yes-please} silently parsing
   * as "no socket" would leave a repository believing it opted in — and {@code docker: "false"}
   * silently parsing as truthy would be the far worse direction. Only a YAML boolean is accepted;
   * SnakeYAML already resolves {@code true}/{@code false}/{@code yes}/{@code no} to one, so a repo
   * pays nothing for the strictness except finding out about its typos.
   */
  private static boolean optionalDocker(Map<?, ?> step, int index) {
    Object value = step.get("docker");
    if (value == null) {
      return false;
    }
    if (!(value instanceof Boolean docker)) {
      throw new CiConfigException(
          "Step " + index + ": 'docker' must be a boolean, got: " + typeOf(value));
    }
    return docker;
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
