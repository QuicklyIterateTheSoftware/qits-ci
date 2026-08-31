package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
import eu.wohlben.qits.ci.control.CiPipeline.BranchFilter;
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
 * rather than an oversight: see {@link CiEventTriggerParser}. {@code artifacts:} is parsed there
 * too, because only a trigger file can carry one — its key name lives here so both sides of the
 * two-way rule spell it once.
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

  /**
   * The artifacts a trigger file's pipeline publishes. A third member of the two-way rule: it is
   * read only from a {@code ci-event-*.yml} and is an error in {@code ci-post-receive.yml}, because
   * what it declares is announced with the <em>triggering event's</em> version and a push carries
   * none.
   */
  static final String ARTIFACTS_KEY = "artifacts";

  /**
   * Where a triggered run checks out — two payload dot-paths, {@code branch} and {@code sha}. A
   * fourth member of the two-way rule (trigger files only): a push run's checkout IS the push, so
   * the key could only ever be inert in {@code ci-post-receive.yml}. Absent, an event run builds
   * the head of {@code main}, exactly as every trigger always has.
   */
  static final String CHECKOUT_KEY = "checkout";

  /**
   * Whether a red run of this pipeline should stand in the way of releasing its commit — trigger
   * files only, default {@code true}. The platform's userflow pipelines are the reason it exists:
   * they are non-gating by design ("a red story costs a fix-forward cycle, not an image"), and the
   * release-quality-gates build gate needs that stated as data rather than known by file name. A
   * push run is always gating, so the key would be inert in {@code ci-post-receive.yml} — the same
   * two-way rule as {@code checkout:}.
   */
  static final String GATING_KEY = "gating";

  static final String CHECKOUT_BRANCH = "branch";

  static final String CHECKOUT_SHA = "sha";

  /** The per-step branch filter — legal in a pipeline file, an error in a trigger file. */
  static final String BRANCHES_KEY = "branches";

  /** Who the step's container runs as. Legal in both file kinds; refused beside {@code docker}. */
  static final String USER_KEY = "user";

  /**
   * What a {@code user:} may spell: a passwd name or a bare uid. Deliberately narrower than
   * anything docker accepts — a value here becomes an argv element, so it must not open with a
   * {@code -} or carry a {@code :} (which is {@code --user}'s own {@code user:group} separator and
   * would let one word declare a group nobody wrote down).
   */
  private static final String USER_CHARS = "[a-z0-9_][a-z0-9_-]*";

  /**
   * The matcher vocabulary, spelled once for everything that reads one. {@code regex} is
   * deliberately absent; adding one is a decision about the DSL, not a convenience, and it belongs
   * in a plan before it belongs here.
   */
  static final String EXACT = "exact";

  static final String PREFIX = "prefix";

  static final String EXISTS = "exists";

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
   * The {@code steps:} list of a pipeline file, where a step may bind itself to branches. An absent
   * or empty list yields an empty pipeline — the opt-in file is visible, which is what a trivially
   * green run records.
   */
  static CiPipeline steps(Map<?, ?> root) {
    return steps(root, null);
  }

  /**
   * The same list read from a trigger file, where {@code branches:} on a step is a <b>parse
   * error</b> naming {@code configPath}.
   *
   * <p>The key is refused rather than ignored because on that path it has only two possible
   * behaviours and both are silent. An event-triggered run always builds the head of {@code main},
   * so {@code exact: main} would be inert decoration and anything else a step that is <em>always</em>
   * skipped — indistinguishable at a glance from one that never got its turn. Allow-but-inert is the
   * trap; this is the two-way rule's own argument, moved down one level from the top-level keys to
   * the step.
   */
  static CiPipeline stepsRejectingBranches(Map<?, ?> root, String configPath) {
    return steps(root, configPath);
  }

  private static CiPipeline steps(Map<?, ?> root, String rejectBranchesIn) {
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
      boolean docker = optionalDocker(step, i);
      steps.add(
          new CiStepDecl(
              requireString(step, "image", i),
              requireString(step, "script", i),
              optionalTimeoutSeconds(step, i),
              docker,
              optionalUser(step, i, docker),
              optionalBranches(step, i, rejectBranchesIn)));
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

  /**
   * The optional per-step {@code user}: who the container's first process runs as. Absent means the
   * image's own default — root, for every base image the platform builds on — which is what every
   * step has had until now.
   *
   * <p>It is declared here rather than done in the script because it <b>cannot</b> be done in the
   * script. A step container is started {@code --cap-drop=ALL}, so it holds neither CAP_SETUID nor
   * CAP_SETGID and {@code su} cannot switch user at all, and neither CAP_CHOWN, so even root cannot
   * {@code chown} the checkout. The adduser/chown/su preamble two pipelines carried was impossible
   * by construction; measured 2026-08-12 on qits-containers, as
   * {@code chown: /workspace: Operation not permitted}. The one moment a user can be chosen is the
   * {@code docker run}, which is what this key reaches.
   *
   * <p><b>{@code user} beside {@code docker: true} is refused.</b> A step holding the host's docker
   * socket stays root: the socket's ownership is the host's fact and not this repository's, so a
   * non-root step could not drive it — and the refusal is here rather than at the socket because a
   * permission denied halfway through a publish is the expensive way to learn it. Widening this is
   * a decision about the socket's group, not a convenience.
   *
   * <p>Held to the {@code timeout-seconds}/{@code docker} standard: a value this parser knows and
   * cannot read is a config error, never a quiet default. A mis-spelled user that fell back to root
   * would run the suite as root and fail deep inside a test with initdb's own message.
   */
  private static String optionalUser(Map<?, ?> step, int index, boolean docker) {
    Object value = step.get(USER_KEY);
    if (value == null) {
      return "";
    }
    if (!(value instanceof String user) || user.isBlank()) {
      throw new CiConfigException(
          "Step " + index + ": '" + USER_KEY + "' must be a name or a uid, got: " + typeOf(value));
    }
    if (!user.matches(USER_CHARS)) {
      throw new CiConfigException(
          "Step "
              + index
              + ": '"
              + USER_KEY
              + "' must be a lowercase name or a bare uid ("
              + USER_CHARS
              + "), got: '"
              + user
              + "'");
    }
    if (docker) {
      throw new CiConfigException(
          "Step "
              + index
              + ": '"
              + USER_KEY
              + "' cannot be combined with 'docker: true' — a step holding the host's docker socket"
              + " runs as root, because the socket's ownership is the host's and not this"
              + " repository's. Split the work into two steps.");
    }
    return user;
  }

  /**
   * The optional per-step {@code branches}: the branches this step is bound to, as a list of matcher
   * mappings — entries OR'd, a mapping's keys AND'd.
   *
   * <p>Absent means <b>the step runs on every branch</b>, which is the whole backward-compatibility
   * clause: every pipeline written before this key existed keeps its behaviour byte for byte. An
   * <b>empty list is a config error</b> rather than either reading of it, because both readings
   * already have an unambiguous spelling — omit the key for "every branch", delete the step for
   * "none" — and an ambiguity with two better spellings is a parse error.
   *
   * <p>Held to the {@code timeout-seconds}/{@code docker} standard, for the sharper of the two
   * standing reasons: a silently mis-parsed filter would either run a scoped step everywhere or skip
   * it forever, and both directions are silent. Unknown per-step keys stay ignored, unchanged.
   */
  private static List<BranchFilter> optionalBranches(
      Map<?, ?> step, int index, String rejectBranchesIn) {
    Object value = step.get(BRANCHES_KEY);
    if (value == null) {
      return List.of();
    }
    if (rejectBranchesIn != null) {
      throw new CiConfigException(
          rejectBranchesIn
              + ": step "
              + index
              + " declares '"
              + BRANCHES_KEY
              + "' — an event-triggered run always builds the head of "
              + CiRunService.MAIN_BRANCH
              + ", so a branch filter there is either inert or a step that can never run. A"
              + " condition over the event's payload is what 'when' already is.");
    }
    if (!(value instanceof List<?> list)) {
      throw new CiConfigException(
          "Step " + index + ": 'branches' must be a list of matchers, got: " + typeOf(value));
    }
    if (list.isEmpty()) {
      throw new CiConfigException(
          "Step "
              + index
              + ": 'branches' is empty — omit the key to run the step on every branch, or delete"
              + " the step to run it on none");
    }
    List<BranchFilter> filters = new ArrayList<>(list.size());
    for (Object entry : list) {
      filters.add(branchFilter(entry, index));
    }
    return List.copyOf(filters);
  }

  /** One entry: a mapping of matchers over the run's branch, AND'd. */
  private static BranchFilter branchFilter(Object raw, int index) {
    if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
      throw new CiConfigException(
          "Step "
              + index
              + ": each 'branches' entry must carry a matcher such as { prefix: maintenance/ },"
              + " got: "
              + typeOf(raw));
    }
    List<Matcher> matchers = new ArrayList<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      matchers.add(branchMatcher(entry.getKey(), entry.getValue(), index));
    }
    return new BranchFilter(List.copyOf(matchers));
  }

  /**
   * One matcher over the branch. The vocabulary is {@code exact} and {@code prefix} and nothing
   * else: the branch is always present, so {@code exists} could only ever say yes, and {@code regex}
   * stays out because {@code prefix: maintenance/} spells the requirement that asked for it with no
   * anchoring, escaping or ReDoS question.
   */
  private static Matcher branchMatcher(Object key, Object value, int index) {
    if (!(key instanceof String matcher)) {
      throw new CiConfigException(
          "Step " + index + ": 'branches' has a non-string matcher key: " + typeOf(key));
    }
    return switch (matcher) {
      case EXACT -> Matcher.exact(requireBranchValue(value, index, EXACT));
      case PREFIX -> Matcher.prefix(requireBranchValue(value, index, PREFIX));
      default ->
          throw new CiConfigException(
              "Step "
                  + index
                  + ": 'branches' declares an unknown matcher '"
                  + matcher
                  + "' — a branch filter knows "
                  + EXACT
                  + " and "
                  + PREFIX
                  + " (a branch is always there, so '"
                  + EXISTS
                  + "' could only ever say yes)");
    };
  }

  /** The compared value, always a non-blank string — the same rule a selection's matchers carry. */
  private static String requireBranchValue(Object value, int index, String matcher) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new CiConfigException(
          "Step "
              + index
              + ": 'branches' declares '"
              + matcher
              + "' with "
              + typeOf(value)
              + " — matcher values are non-empty strings (quote it: "
              + matcher
              + ": \""
              + value
              + "\")");
    }
    return text;
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
