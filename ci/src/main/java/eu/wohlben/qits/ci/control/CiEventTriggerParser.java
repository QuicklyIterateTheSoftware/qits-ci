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
 * trigger — {@code event:}, the exact envelope name, and {@code when:}, the selection — and the
 * optional {@code artifacts:} declaration of what the pipeline publishes.
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
 * <h2>{@code repoId:} names the repository's ADDRESSABLE NAME</h2>
 *
 * <p>The matcher above reads as "the repository this event is about", and after the repository
 * identity campaign that is the repository's <b>name</b> — the second half of the one public address
 * {@code /git/<projectId>/<repoName>}. An {@code SCM*} event carries {@code repoName} filled from
 * the address the push arrived on, and a condition on {@code repoId} is evaluated against it
 * whenever it is there.
 *
 * <p><b>The id is the legacy fallback.</b> An event with no {@code repoName} — a push on the
 * internal id-addressed route, and every event published before the campaign — is matched against
 * its {@code repoId} exactly as it always was. That is what keeps the estate's existing files
 * working unedited on both sides of the cutover: before it the storage id and the name agree, and
 * after it the name field answers. The key keeps its spelling deliberately, because renaming it
 * would break every one of those files to say something they already mean. The alias itself is
 * {@code CiEventSelectionEvaluator.resolveAddressable}, applied at evaluation rather than here,
 * since whether the fallback applies is a property of the arriving event and not of the file.
 *
 * <p>A <b>release pipeline</b> is the same file with the fourth key: it selects an {@code SCMRelease}
 * naming its own repository, checks out the released tag, publishes — and declares what it published,
 * so that a green run announces one {@code SoftwareRelease} per artifact. See {@link CiArtifact}.
 *
 * <pre>{@code
 * event: SCMRelease
 * when:
 *   - repository: { exact: qits-spa-ui-components }
 * artifacts:
 *   - { type: npm, name: "@qits/ui-components" }
 * steps:
 *   - image: qits/build-images/node-base:latest
 *     script: ./publish-tag.sh
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
 * a newer qits-ci. This file does not: {@code event}, {@code when}, {@code steps} and {@code
 * artifacts} are the whole vocabulary and anything else is an error. The reason is what the two files' unknown keys mean. In
 * a pipeline, an unread key costs a feature that was not there yet. In a <b>selection</b>, an unread
 * key costs <em>correctness</em> — a mistyped {@code wehn:} would parse as "no selection", and an
 * absent {@code when} means <b>unconditional</b>, so the trigger would fire on every event of that
 * name instead of the two the repository meant. Silently widening a selection is the one failure
 * mode this file cannot have.
 *
 * <p>The step list keeps its own leniency unchanged, because it is the same pipeline schema and a
 * step must not mean different things in the two files. <b>The one key it subtracts is {@code
 * branches:}</b>, and subtracting is still the point now that {@code checkout:} can name a branch:
 * the run's branch is the trigger's single decision — resolved once, from the payload, before any
 * step exists — so a per-step filter over it is either inert decoration or a step that can never
 * run, and a condition over the event's branch is what {@code when:} already spells. See {@link
 * CiConfigSchema#stepsRejectingBranches}.
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

  /** The prefix that makes a file in {@link #CONFIG_DIR} a repository's own event trigger. */
  public static final String CONFIG_PREFIX = CONFIG_DIR + "ci-event-";

  /**
   * The prefix that makes a file in {@link #CONFIG_DIR} a <b>platform</b> event trigger — one file,
   * in one configured repository, evaluated for every arriving event and recorded against the
   * repository the payload names. See {@link CiTriggerScope}.
   *
   * <p>It does not start with {@link #CONFIG_PREFIX}, so the two sets never overlap and a repository
   * that carries both kinds declares two pipelines rather than one ambiguous file.
   */
  public static final String PLATFORM_CONFIG_PREFIX = CONFIG_DIR + "ci-platform-event-";

  public static final String CONFIG_SUFFIX = ".yml";

  /**
   * The whole top-level vocabulary. Anything else is an error — see the class javadoc for why this
   * file is strict where its sibling is lenient.
   */
  private static final Set<String> TOP_LEVEL_KEYS =
      Set.of(
          CiConfigSchema.EVENT_KEY,
          CiConfigSchema.WHEN_KEY,
          CiConfigSchema.STEPS_KEY,
          CiConfigSchema.ARTIFACTS_KEY,
          CiConfigSchema.GATING_KEY,
          CiConfigSchema.CHECKOUT_KEY);

  /** The whole of an artifact declaration. Anything else in that mapping is an error. */
  private static final Set<String> ARTIFACT_KEYS = Set.of("type", "name");

  /** The whole of a checkout declaration. Anything else in that mapping is an error. */
  private static final Set<String> CHECKOUT_KEYS =
      Set.of(
          CiConfigSchema.CHECKOUT_BRANCH,
          CiConfigSchema.CHECKOUT_SHA,
          CiConfigSchema.CHECKOUT_OPTIONAL);

  /**
   * What a payload dot-path may spell — one rule for {@code when:}'s keys and {@code checkout:}'s
   * values, extracted so the two grammars cannot drift.
   */
  private static final String DOT_PATH = "[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*";

  /**
   * The whole matcher vocabulary, spelled in {@link CiConfigSchema} because a step's {@code
   * branches:} filter reads two of the same three words. {@code regex} is deliberately absent;
   * adding one is a decision about the DSL, not a convenience, and it belongs in the plan before it
   * belongs here.
   */
  private static final String EXACT = CiConfigSchema.EXACT;

  private static final String PREFIX = CiConfigSchema.PREFIX;

  private static final String EXISTS = CiConfigSchema.EXISTS;

  /**
   * Whether a repository-tree path is one of this parser's files. The {@code *} is freely chosen and
   * ignored, but it is bounded to a plain, path-safe slug: the value arrives from a {@code git
   * ls-tree} of another repository's tree and is handed straight back to {@code git show} as part of
   * an argv, so what it may contain is decided here rather than trusted. A file whose name falls
   * outside this is simply not a trigger file — the same answer an unrelated file in that directory
   * gets.
   */
  public static boolean isTriggerPath(String path) {
    return isTriggerPath(path, CONFIG_PREFIX);
  }

  /** Whether a path is a <b>platform</b> trigger file — the same rule, one prefix over. */
  public static boolean isPlatformTriggerPath(String path) {
    return isTriggerPath(path, PLATFORM_CONFIG_PREFIX);
  }

  /** The shared rule: this prefix, the {@code .yml} suffix, and a plain slug in between. */
  static boolean isTriggerPath(String path, String prefix) {
    if (path == null
        || !path.startsWith(prefix)
        || !path.endsWith(CONFIG_SUFFIX)
        || path.length() <= prefix.length() + CONFIG_SUFFIX.length()) {
      return false;
    }
    String name = path.substring(prefix.length(), path.length() - CONFIG_SUFFIX.length());
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
        // The step schema is shared verbatim, with one key subtracted rather than redefined: see
        // CiConfigSchema#stepsRejectingBranches. A step means one thing in both files, and where it
        // cannot mean anything it is an error rather than a second meaning.
        CiConfigSchema.stepsRejectingBranches(root, configPath),
        parseArtifacts(root.get(CiConfigSchema.ARTIFACTS_KEY), configPath),
        parseGating(root.get(CiConfigSchema.GATING_KEY), configPath),
        parseCheckout(root.get(CiConfigSchema.CHECKOUT_KEY), configPath));
  }

  private static void rejectUnknownTopLevelKeys(Map<?, ?> root, String configPath) {
    for (Object key : root.keySet()) {
      if (!(key instanceof String name) || !TOP_LEVEL_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": unknown top-level key '"
                + key
                + "' — an event trigger declares only 'event', 'when', 'steps', 'artifacts',"
                + " 'gating' and 'checkout'");
      }
    }
  }

  /**
   * {@code gating: false} is the whole of what the key may say — absent is {@code true}, and on
   * this file's standing rule anything that is not a YAML boolean is an error rather than a guess:
   * a gating flag that silently parsed to a default would let a red pipeline block releases nobody
   * meant it to block, or wave through one somebody did.
   */
  private static boolean parseGating(Object raw, String configPath) {
    if (raw == null) {
      return true;
    }
    if (raw instanceof Boolean gating) {
      return gating;
    }
    throw new CiConfigException(
        configPath + ": 'gating' must be true or false, got: " + raw);
  }

  /**
   * Where a run of this trigger checks out — {@code checkout: { branch: <path>, sha: <path> }},
   * both dot-paths into the payload, both mandatory, plus an optional {@code optional: true}. Null
   * (the key absent) is today's behavior byte-for-byte: the run builds the head of {@code main}.
   *
   * <p><b>Both paths, not one.</b> The run row's ref is load-bearing everywhere (the daemon's clone
   * is {@code --branch $QITS_CI_BRANCH} + checkout {@code $QITS_CI_SHA}, the announcement carries
   * it, the queue collapse keys on it), and a sha with no ref whose history holds it is not
   * something the daemon can fetch. So an event with no ref in its payload — {@code SCMPublishTag}
   * — cannot use {@code checkout:}; its pipelines keep the script-level tag-fetch dance.
   *
   * <p><b>{@code branch} is a path to a REF NAME, and a tag is one.</b> The word is the run row's
   * ("branch" is what a run has always called its ref), not a claim about what the value may be:
   * {@code git clone --branch} resolves a tag name as readily as a head, so {@code checkout: {
   * branch: version, sha: commitSha }} over an {@code SCMRelease} anchors the run at the released
   * tag and its commit. That took no engine knowledge of tags and must not grow any — the whole
   * mechanism is that a ref name is a ref name.
   *
   * <p><b>{@code optional: true} is the one loosening, and it is opt-in per file.</b> Without it, a
   * payload the paths do not resolve in costs this file its run, which is the right answer for a
   * push pipeline whose entire subject is the pushed commit. A pipeline whose event GREW its
   * coordinate needs the other answer: an {@code SCMRelease} from before {@code commitSha} existed —
   * a replay, an older publisher — resolves {@code version} and not {@code commitSha}, and refusing
   * it would turn a strictly additive field into releases that silently never build. With the flag,
   * such an event is built at {@code main}'s head, which is precisely what this file did before it
   * declared a checkout at all. Default {@code false}, so nothing that does not ask for it moves.
   *
   * <p>Strict in every direction otherwise, on this file's standing reason: a checkout that silently
   * parsed to nothing would build main's head while claiming the event's commit.
   */
  private static CiEventTrigger.Checkout parseCheckout(Object raw, String configPath) {
    if (raw == null) {
      return null;
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": 'checkout' must be a mapping of { branch: <payload path>, sha: <payload path>"
              + " }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String name) || !CHECKOUT_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": 'checkout' declares an unknown key '"
                + key
                + "' — it is exactly { branch, sha, optional }");
      }
    }
    return new CiEventTrigger.Checkout(
        requireCheckoutPath(map.get(CiConfigSchema.CHECKOUT_BRANCH), configPath, "branch"),
        requireCheckoutPath(map.get(CiConfigSchema.CHECKOUT_SHA), configPath, "sha"),
        parseCheckoutOptional(map.get(CiConfigSchema.CHECKOUT_OPTIONAL), configPath));
  }

  /**
   * {@code optional: true} is the whole of what the key may say — absent is {@code false}, and
   * anything that is not a YAML boolean is an error rather than a guess, exactly as {@code gating:}
   * is. The two failures either way are silent ones: a value that parsed to true by accident would
   * let a release pipeline build main's head believing it built a tag, and one that parsed to false
   * by accident would lose the runs this flag exists to keep.
   */
  private static boolean parseCheckoutOptional(Object raw, String configPath) {
    if (raw == null) {
      return false;
    }
    if (raw instanceof Boolean optional) {
      return optional;
    }
    throw new CiConfigException(
        configPath + ": checkout.optional must be true or false, got: " + raw);
  }

  private static String requireCheckoutPath(Object value, String configPath, String member) {
    if (value == null) {
      throw new CiConfigException(
          configPath
              + ": 'checkout' declares no '"
              + member
              + "' — it is the payload dot-path the run checks out, e.g. '"
              + member
              + ": "
              + member
              + "'");
    }
    if (!(value instanceof String path) || path.isBlank() || !path.matches(DOT_PATH)) {
      throw new CiConfigException(
          configPath
              + ": checkout."
              + member
              + " '"
              + value
              + "' is not a dot-path into the payload — navigation only, no wildcards, filters or"
              + " indexing");
    }
    return path;
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
    if (!path.matches(DOT_PATH)) {
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

  /**
   * The optional {@code artifacts:} declaration — what this pipeline publishes, so that a green run
   * can announce it. Absent means the file declares none, which is the ordinary case and publishes
   * nothing; see {@link CiArtifact} for why a declaration rather than a report.
   *
   * <pre>{@code
   * artifacts:
   *   - { type: npm, name: "@qits/ui-components" }
   *   - { type: docker, name: qits/qits-stt }
   * }</pre>
   *
   * <p>Strict in every direction, on this file's standing reason: a declaration that silently parsed
   * to nothing would be a release nothing downstream ever hears about, which reads exactly like a
   * train that quietly did not roll. An <b>empty list</b> is refused rather than read as "none",
   * because omitting the key already spells that unambiguously — the same argument {@code branches:
   * []} loses on.
   */
  private static List<CiArtifact> parseArtifacts(Object raw, String configPath) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new CiConfigException(
          configPath
              + ": 'artifacts' must be a list of { type: …, name: … } mappings, got: "
              + CiConfigSchema.typeOf(raw));
    }
    if (list.isEmpty()) {
      throw new CiConfigException(
          configPath
              + ": 'artifacts' is empty — omit the key to publish nothing, or name what this"
              + " pipeline publishes");
    }
    List<CiArtifact> artifacts = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      artifacts.add(parseArtifact(list.get(i), configPath, i));
    }
    return List.copyOf(artifacts);
  }

  private static CiArtifact parseArtifact(Object raw, String configPath, int index) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " must be a mapping of { type: …, name: … }, got: "
              + CiConfigSchema.typeOf(raw));
    }
    for (Object key : map.keySet()) {
      if (!(key instanceof String name) || !ARTIFACT_KEYS.contains(name)) {
        throw new CiConfigException(
            configPath
                + ": artifact "
                + index
                + " declares an unknown key '"
                + key
                + "' — an artifact is exactly { type, name }");
      }
    }
    return new CiArtifact(
        requireArtifactType(map.get("type"), configPath, index),
        requireArtifactName(map.get("name"), configPath, index));
  }

  private static CiArtifact.Type requireArtifactType(
      Object value, String configPath, int index) {
    CiArtifact.Type type = value instanceof String keyword ? CiArtifact.Type.of(keyword) : null;
    if (type == null) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " declares type '"
              + value
              + "' — this qits-ci publishes "
              + CiArtifact.Type.vocabulary());
    }
    return type;
  }

  /**
   * The exact package name, as its registry knows it. Not validated beyond non-blank: what a name
   * may contain is npm's and the registry's business, and a rule guessed here would refuse a name
   * that publishes fine.
   */
  private static String requireArtifactName(Object value, String configPath, int index) {
    if (!(value instanceof String name) || name.isBlank()) {
      throw new CiConfigException(
          configPath
              + ": artifact "
              + index
              + " declares no 'name' — it is the exact package name, and a scoped one needs"
              + " quoting ('@' is a reserved YAML indicator): name: \"@qits/ui-components\"");
    }
    return name;
  }
}
