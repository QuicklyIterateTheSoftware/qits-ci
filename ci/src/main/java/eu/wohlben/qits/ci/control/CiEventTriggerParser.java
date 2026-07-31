package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiEventSelection.Group;
import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
import eu.wohlben.qits.ci.control.CiEventSelection.PathCondition;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a repo-committed {@code .config/qits/ci-event-*.yml}: the existing pipeline schema (shared
 * with {@link CiConfigParser} through {@link CiConfigSchema}) plus the two keys that make it a
 * trigger — {@code event:}, the exact envelope name, and {@code when:}, the selection.
 *
 * <pre>{@code
 * event: BuildSuccessful
 * when:
 *   - repoId: { exact: qits-spa-ui-components }
 *     branch: { exact: main }
 * steps:
 *   - image: qits/build-images/node-base:latest
 *     script: ./bump-and-push.sh
 * }</pre>
 *
 * <h2>The two-way rule</h2>
 *
 * <p>A {@code ci-event-*.yml} <b>without</b> {@code event:} is a parse error, exactly as a {@code
 * ci-post-receive.yml} <b>with</b> {@code event:}/{@code when:} is one on the other side. The two
 * trigger types never blur, and neither mistake is allowed to be silent — a trigger that cannot be
 * parsed must not quietly never fire, which is indistinguishable from a selection that never matched.
 *
 * <h2>Strict where {@link CiConfigParser} is lenient, and that asymmetry is deliberate</h2>
 *
 * <p>{@code ci-post-receive.yml} ignores unknown top-level keys so a repository can carry config for
 * a newer qits-ci. This file does not: {@code event}, {@code when} and {@code steps} are the whole
 * vocabulary and anything else is an error. The reason is what the two files' unknown keys mean. In
 * a pipeline, an unread key costs a feature that was not there yet. In a <b>selection</b>, an unread
 * key costs <em>correctness</em> — a mistyped {@code wehn:} would parse as "no selection", and an
 * absent {@code when} means <b>unconditional</b>, so the trigger would fire on every event of that
 * name instead of the two the repository meant. Silently widening a selection is the one failure
 * mode this file cannot have.
 *
 * <p>The step list keeps its own leniency unchanged, because it is the same pipeline schema and a
 * step must not mean different things in the two files.
 *
 * <h2>Failures are per file</h2>
 *
 * <p>Every problem here is a {@link CiConfigException} naming the file. The engine catches it, warns
 * with the repository and the path, and moves on to the repository's <em>other</em> trigger files: one
 * broken selection never disables the ones beside it.
 */
@ApplicationScoped
public class CiEventTriggerParser {

  /** The directory both trigger types live in. */
  public static final String CONFIG_DIR = ".config/qits/";

  /** The prefix that makes a file in {@link #CONFIG_DIR} an event trigger. */
  public static final String CONFIG_PREFIX = CONFIG_DIR + "ci-event-";

  public static final String CONFIG_SUFFIX = ".yml";

  /**
   * The whole top-level vocabulary. Anything else is an error — see the class javadoc for why this
   * file is strict where its sibling is lenient.
   */
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(CiConfigSchema.EVENT_KEY, CiConfigSchema.WHEN_KEY, CiConfigSchema.STEPS_KEY);

  /**
   * The whole matcher vocabulary. {@code regex} is deliberately absent; adding one is a decision
   * about the DSL, not a convenience, and it belongs in the plan before it belongs here.
   */
  private static final String EXACT = "exact";

  private static final String PREFIX = "prefix";

  private static final String EXISTS = "exists";

  /**
   * Whether a repository-tree path is one of this parser's files. The {@code *} is freely chosen and
   * ignored, but it is bounded to a plain, path-safe slug: the value arrives from a {@code git
   * ls-tree} of another repository's tree and is handed straight back to {@code git show} as part of
   * an argv, so what it may contain is decided here rather than trusted. A file whose name falls
   * outside this is simply not a trigger file — the same answer an unrelated file in that directory
   * gets.
   */
  public static boolean isTriggerPath(String path) {
    if (path == null
        || !path.startsWith(CONFIG_PREFIX)
        || !path.endsWith(CONFIG_SUFFIX)
        || path.length() <= CONFIG_PREFIX.length() + CONFIG_SUFFIX.length()) {
      return false;
    }
    String name =
        path.substring(CONFIG_PREFIX.length(), path.length() - CONFIG_SUFFIX.length());
    return name.length() <= 64 && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
  }

  /** Parses one trigger file's content. {@code configPath} is carried through onto the result. */
  public CiEventTrigger parse(String configPath, String content) {
    // Strict about duplicate keys, unlike ci-post-receive.yml: a silently dropped condition widens
    // a selection, which is the one failure mode this file may not have. CiConfigSchema#load argues
    // the asymmetry in full.
    Map<?, ?> root = CiConfigSchema.load(content, true);
    if (root == null) {
      throw new CiConfigException(
          configPath + " is empty — an event trigger must at least declare 'event'");
    }
    rejectUnknownTopLevelKeys(root, configPath);
    return new CiEventTrigger(
        configPath,
        requireEventName(root, configPath),
        parseWhen(root.get(CiConfigSchema.WHEN_KEY), configPath),
        CiConfigSchema.steps(root));
  }

  private static void rejectUnknownTopLevelKeys(Map<?, ?> root, String configPath) {
    for (Object key : root.keySet()) {
      if (!(key instanceof String name) || !TOP_LEVEL_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": unknown top-level key '"
                + key
                + "' — an event trigger declares only 'event', 'when' and 'steps'");
      }
    }
  }

  /**
   * The envelope name this trigger listens for, matched <b>exactly</b> against the frame's {@code
   * name}. Not part of {@code when}: it is the signature, not a condition over the payload.
   */
  private static String requireEventName(Map<?, ?> root, String configPath) {
    Object value = root.get(CiConfigSchema.EVENT_KEY);
    if (value == null) {
      throw new CiConfigException(
          configPath
              + " declares no 'event' — a "
              + CONFIG_PREFIX
              + "*"
              + CONFIG_SUFFIX
              + " names the event it listens for, or it is a "
              + CiConfigParser.CONFIG_PATH
              + " in the wrong file");
    }
    if (!(value instanceof String name) || name.isBlank()) {
      throw new CiConfigException(
          configPath + ": 'event' must be the event's name, got: " + CiConfigSchema.typeOf(value));
    }
    return name;
  }

  /**
   * The selection. Absent or empty means {@link CiEventSelection#unconditional()} — see that class
   * for why, and note that the docs say it too, since it is the one default a trigger author has to
   * know about.
   */
  private static CiEventSelection parseWhen(Object raw, String configPath) {
    if (raw == null) {
      return CiEventSelection.unconditional();
    }
    if (!(raw instanceof List<?> list)) {
      throw new CiConfigException(
          configPath
              + ": 'when' must be a list of match groups, got: "
              + CiConfigSchema.typeOf(raw));
    }
    if (list.isEmpty()) {
      return CiEventSelection.unconditional();
    }
    List<Group> groups = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      groups.add(parseGroup(list.get(i), configPath, i));
    }
    return new CiEventSelection(List.copyOf(groups));
  }

  private static Group parseGroup(Object raw, String configPath, int index) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": 'when' group "
              + index
              + " must be a mapping of path to matcher, got: "
              + CiConfigSchema.typeOf(raw));
    }
    if (map.isEmpty()) {
      // An empty group would match every event, which is what an empty `when` already means and is
      // never what a repository writing a group meant.
      throw new CiConfigException(
          configPath + ": 'when' group " + index + " is empty — it would match every event");
    }
    List<PathCondition> conditions = new ArrayList<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String path = requirePath(entry.getKey(), configPath, index);
      conditions.add(new PathCondition(path, parseMatchers(entry.getValue(), configPath, path)));
    }
    return new Group(List.copyOf(conditions));
  }

  /**
   * A dot-path into the payload. Navigation only: no wildcards, no filters, no indexing — so the
   * shape is checked here rather than discovered by a walk that quietly resolves nothing.
   */
  private static String requirePath(Object key, String configPath, int groupIndex) {
    if (!(key instanceof String path) || path.isBlank()) {
      throw new CiConfigException(
          configPath
              + ": 'when' group "
              + groupIndex
              + " has a non-string path key: "
              + CiConfigSchema.typeOf(key));
    }
    if (!path.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")) {
      throw new CiConfigException(
          configPath
              + ": '"
              + path
              + "' is not a dot-path into the payload — navigation only, no wildcards, filters or"
              + " indexing");
    }
    return path;
  }

  /** One path's matcher, or a list of them (AND'd — the same-path case a plain map cannot spell). */
  private static List<Matcher> parseMatchers(Object raw, String configPath, String path) {
    if (raw instanceof List<?> list) {
      if (list.isEmpty()) {
        throw new CiConfigException(
            configPath + ": '" + path + "' declares an empty list of matchers");
      }
      List<Matcher> matchers = new ArrayList<>(list.size());
      for (Object entry : list) {
        matchers.addAll(parseMatcher(entry, configPath, path));
      }
      return List.copyOf(matchers);
    }
    return parseMatcher(raw, configPath, path);
  }

  /**
   * One matcher mapping. A mapping with several keys ({@code {prefix: qits-, exists: true}}) is
   * simply several matchers on the same path, AND'd like everything else in a group.
   */
  private static List<Matcher> parseMatcher(Object raw, String configPath, String path) {
    if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": '"
              + path
              + "' must carry a matcher such as { exact: … }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    List<Matcher> matchers = new ArrayList<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      matchers.add(parseMatcherEntry(entry.getKey(), entry.getValue(), configPath, path));
    }
    return List.copyOf(matchers);
  }

  private static Matcher parseMatcherEntry(
      Object key, Object value, String configPath, String path) {
    if (!(key instanceof String matcher)) {
      throw new CiConfigException(
          configPath + ": '" + path + "' has a non-string matcher key: " + CiConfigSchema.typeOf(key));
    }
    return switch (matcher) {
      case EXACT -> Matcher.exact(requireMatchValue(value, configPath, path, EXACT));
      case PREFIX -> Matcher.prefix(requireMatchValue(value, configPath, path, PREFIX));
      case EXISTS -> {
        if (!(value instanceof Boolean expected)) {
          throw new CiConfigException(
              configPath
                  + ": '"
                  + path
                  + "' declares 'exists' with "
                  + CiConfigSchema.typeOf(value)
                  + " — it takes a boolean");
        }
        yield Matcher.exists(expected);
      }
      default ->
          throw new CiConfigException(
              configPath
                  + ": '"
                  + path
                  + "' declares an unknown matcher '"
                  + matcher
                  + "' — this qits-ci knows "
                  + EXACT
                  + ", "
                  + PREFIX
                  + " and "
                  + EXISTS);
    };
  }

  /**
   * The compared value, always a string. A YAML scalar that resolved to a number or a boolean is
   * <em>not</em> silently stringified: {@code exact: 3} and {@code exact: "3"} would then be the same
   * declaration, and a repository comparing against a JSON number should say so the way it reads it.
   */
  private static String requireMatchValue(
      Object value, String configPath, String path, String matcher) {
    if (!(value instanceof String text)) {
      throw new CiConfigException(
          configPath
              + ": '"
              + path
              + "' declares '"
              + matcher
              + "' with "
              + CiConfigSchema.typeOf(value)
              + " — matcher values are strings (quote it: "
              + matcher
              + ": \""
              + value
              + "\")");
    }
    return text;
  }
}
