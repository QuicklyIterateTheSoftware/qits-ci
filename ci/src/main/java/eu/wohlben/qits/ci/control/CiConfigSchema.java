package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * What {@link CiConfigParser} and {@link CiEventTriggerParser} genuinely share: the YAML load, the
 * {@code steps:} schema, and the two-way rule that keeps the two trigger types from blurring.
 *
 * <p>Deliberately <b>only</b> those three. The two files are the same pipeline schema under two
 * different trigger declarations, so the step list is one implementation — a repo must not discover
 * that {@code timeout-seconds} means something different in a trigger file. What the two do
 * <em>not</em> share is their treatment of unknown top-level keys, and that asymmetry is intentional
 * rather than an oversight: see {@link CiEventTriggerParser}.
 *
 * <p>The load is the {@code QitsConfigParser} pattern throughout — SnakeYAML's {@link
 * SafeConstructor}, plain maps and lists only, never instantiating a class named by repository
 * content. That is also why nothing here needs native-image reflection metadata: a
 * {@code SafeConstructor} produces {@code java.util} collections and boxed primitives and binds to no
 * type of ours.
 */
final class CiConfigSchema {

  /**
   * The two keys that say "this is an event trigger". They are the whole of the two-way rule: their
   * presence in {@code ci-post-receive.yml} is an error, and {@code event}'s absence from a {@code
   * ci-event-*.yml} is one too.
   */
  static final String EVENT_KEY = "event";

  static final String WHEN_KEY = "when";

  static final String STEPS_KEY = "steps";

  private CiConfigSchema() {}

  /**
   * Loads the document root, or null for blank content and an empty document. A non-mapping root is
   * a config error — every schema this repo has is a mapping.
   *
   * <p><b>{@code strictDuplicateKeys} is why this takes a flag rather than being one call.</b> A
   * duplicate key is a defect in either file, but the two failures are not comparable. In a pipeline
   * SnakeYAML keeps the last one and the repository gets a step it can see; in a <b>selection</b> a
   * silently dropped condition <em>widens</em> what the trigger fires on, which is the one failure
   * mode a trigger file may not have. So trigger files are strict and {@code ci-post-receive.yml}
   * keeps the leniency it has always had — tightening it would turn config that works today into a
   * {@code CONFIG_ERROR} run in every repository at once, for a defect nobody has reported.
   */
  static Map<?, ?> load(String content, boolean strictDuplicateKeys) {
    if (content == null || content.isBlank()) {
      return null;
    }
    Object root;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(!strictDuplicateKeys);
      Yaml yaml = new Yaml(new SafeConstructor(options));
      root = yaml.load(content);
    } catch (Exception e) {
      throw new CiConfigException("Invalid YAML: " + e.getMessage(), e);
    }
    if (root == null) {
      return null;
    }
    if (!(root instanceof Map<?, ?> map)) {
      throw new CiConfigException("Expected a mapping at the document root, got: " + typeOf(root));
    }
    return map;
  }

  /**
   * The {@code steps:} list, shared verbatim by both trigger types. An absent or empty list yields an
   * empty pipeline — the opt-in file is visible, which is what a trivially green run records.
   */
  static CiPipeline steps(Map<?, ?> root) {
    Object rawSteps = root.get(STEPS_KEY);
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
   * qits.ci.step-timeout-seconds}, i.e. exactly the behaviour before this key existed — the leniency
   * elsewhere is about keys the parser does not <em>know</em>, and this one it knows, so a value that
   * cannot be a deadline is a config error rather than something quietly ignored. A repo that meant
   * to bound a step and mistyped the number must find out.
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

  static String typeOf(Object value) {
    return value == null ? "null" : value.getClass().getSimpleName();
  }
}
