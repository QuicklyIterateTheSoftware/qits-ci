package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
import eu.wohlben.qits.ci.control.CiPipeline.CiStepDecl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The YAML load and the {@code steps:} schema every committed pipeline file shares.
 *
 * <p>It was extracted when there were <b>two</b> such files — {@code ci-post-receive.yml}, read from
 * a pushed commit, and {@code ci-event-*.yml}, read at a branch head — and it held the third thing
 * they shared: the two-way rule, which made each file's keys a parse error in the other so a
 * declaration in the wrong file could not be mistaken for a trigger that never matched. Per-push CI
 * retired on 2026-09-05 and {@code CiConfigParser} went with it, so there is one file left and the
 * key constants below are simply {@link CiEventTriggerParser}'s. They stay here because the schema
 * and the trigger grammar are still two different things, and because the day a second file kind
 * returns this is where it would be shared from.
 *
 * <p>The load is the {@code QitsConfigParser} pattern throughout — SnakeYAML's {@link
 * SafeConstructor}, plain maps and lists only, never instantiating a class named by repository
 * content. That is also why nothing here needs native-image reflection metadata: a
 * {@code SafeConstructor} produces {@code java.util} collections and boxed primitives and binds to no
 * type of ours.
 */
final class CiConfigSchema {

  /**
   * The key that says "this is an event trigger": the envelope name the file listens for. Its
   * absence from a {@code ci-event-*.yml} is a parse error, because a trigger that names no event
   * can never fire and must not look like one that simply never matched.
   */
  static final String EVENT_KEY = "event";

  static final String WHEN_KEY = "when";

  static final String STEPS_KEY = "steps";

  /**
   * The artifacts a trigger file's pipeline publishes. What it declares is announced with the
   * <em>triggering event's</em> version, which is why it could never have meant anything in the
   * retired push pipeline: a push carries no version at all.
   */
  static final String ARTIFACTS_KEY = "artifacts";

  /**
   * Where a triggered run checks out — two payload dot-paths, {@code branch} and {@code sha}. Absent,
   * an event run builds the head of {@code main}, exactly as every trigger always has.
   */
  static final String CHECKOUT_KEY = "checkout";

  /**
   * Whether a red run of this pipeline should stand in the way of releasing its commit — trigger
   * files only, default {@code true}. The platform's userflow pipelines are the reason it exists:
   * they are non-gating by design ("a red story costs a fix-forward cycle, not an image"), and the
   * release-quality-gates build gate needs that stated as data rather than known by file name.
   */
  static final String GATING_KEY = "gating";

  static final String CHECKOUT_BRANCH = "branch";

  static final String CHECKOUT_SHA = "sha";

  /**
   * Whether an event that does not carry the checkout may still run — {@code optional: true},
   * default {@code false}.
   *
   * <p>Without it, a declared {@code checkout:} whose paths resolve to nothing costs that file its
   * run, which is right for a pipeline whose whole subject is the commit the event names — {@code
   * ci-event-release-request.yml} gates a FOLD, and an event naming no fold has nothing truthful to
   * record a row against. It is <b>wrong for a pipeline whose event grew the coordinate</b>. A release pipeline anchored at the tag reads {@code commitSha},
   * a field {@code SCMRelease} did not always carry, and every event published before it — a replay,
   * an older publisher, a rolled-back one — carries no such key at all. Refusing those would turn a
   * strictly additive event change into releases that silently never build.
   *
   * <p>So {@code optional: true} says: build the event's commit when the event names one, and
   * otherwise build the head of {@code main}, which is exactly what a file with no {@code checkout:}
   * does. It is per file and opt-in, so no pipeline that does not ask for it moves.
   */
  static final String CHECKOUT_OPTIONAL = "optional";

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
   * duplicate key is a defect wherever it appears, but the failures are not comparable. In a plain
   * pipeline SnakeYAML keeps the last one and the repository gets a step it can see; in a
   * <b>selection</b> a silently dropped condition <em>widens</em> what the trigger fires on, which is
   * the one failure mode a trigger file may not have. So trigger files pass {@code true}. The flag
   * survives the retirement of the lenient caller ({@code CiConfigParser}, with per-push CI on
   * 2026-09-05) because it is the argument, not the caller, that is worth keeping: a second file kind
   * that is a pipeline and not a selection would pass {@code false} for exactly this reason.
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
   * The {@code steps:} list. An absent or empty list yields an empty pipeline — a trigger file that
   * matched and declares nothing to verify is a trivially green run rather than an error.
   *
   * <p><b>{@code branches:} on a step is a parse error</b> naming {@code configPath}, and that
   * outlived the key it refuses. A step could declare a filter over the run's branch while there was
   * a pipeline file whose branch was the push's; on this path it has only two possible behaviours
   * and both are silent, because an event-triggered run's branch is decided by the trigger before
   * any step exists — {@code exact: main} would be inert decoration and anything else a step that is
   * <em>always</em> skipped, indistinguishable at a glance from one that never got its turn.
   * Allow-but-inert is the trap the whole schema refuses. Per-push CI retired on 2026-09-05 and the
   * filter went with it entirely, so what is left here is the refusal: a repository that still
   * carries the key is told, rather than having it quietly ignored.
   */
  static CiPipeline steps(Map<?, ?> root, String configPath) {
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
      rejectBranches(step, i, configPath);
      boolean docker = optionalDocker(step, i);
      steps.add(
          new CiStepDecl(
              requireString(step, "image", i),
              requireString(step, "script", i),
              optionalTimeoutSeconds(step, i),
              docker,
              optionalUser(step, i, docker),
              optionalStepGating(step, i)));
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
   * The optional per-step {@code gating}: whether a failure of <b>this step</b> is a verdict about
   * the commit. Absent means true, which is every pipeline written before the key existed.
   *
   * <p><b>It is the same word as the top-level {@link #GATING_KEY} one level down, and that is the
   * point rather than a collision.</b> The file-level key says what the whole pipeline is worth to a
   * release gate; this one says what one step is worth, and the run's verdict is the AND of the two
   * — a non-gating file cannot be made gating by a step, and a gating file's non-gating step
   * produces a non-gating red. That is what lets a repository's single QA pipeline carry the build
   * and its tests as the gating half and the userflow publish as the non-gating half in ONE file,
   * which is what replaced the two-file split (see {@link CiPipeline.CiStepDecl}).
   *
   * <p>It was legal in <b>both</b> file kinds while there were two, unlike {@code checkout:} and the
   * file-level {@code gating:} — the step schema is one implementation on purpose, a step must not
   * mean two things, and a push pipeline whose last step published docs was the identical "this half
   * must not cost the image" case.
   *
   * <p>Held to the {@code timeout-seconds}/{@code docker} standard, and for the sharper of the two
   * reasons: {@code gating: "false"} silently parsing as truthy would hold a commit for a failure
   * nobody meant to gate on, and the other direction would wave one through.
   */
  private static boolean optionalStepGating(Map<?, ?> step, int index) {
    Object value = step.get(GATING_KEY);
    if (value == null) {
      return true;
    }
    if (!(value instanceof Boolean gating)) {
      throw new CiConfigException(
          "Step " + index + ": '" + GATING_KEY + "' must be a boolean, got: " + typeOf(value));
    }
    return gating;
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

  /** A step declaring {@code branches:} is refused — see {@link #steps} for why the key is gone. */
  private static void rejectBranches(Map<?, ?> step, int index, String configPath) {
    if (!step.containsKey(BRANCHES_KEY)) {
      return;
    }
    throw new CiConfigException(
        configPath
            + ": step "
            + index
            + " declares '"
            + BRANCHES_KEY
            + "' — a run's branch is the trigger's single decision, made before any step exists, so"
            + " a per-step filter over it is either inert or a step that can never run. A condition"
            + " over the event's payload is what 'when' already is.");
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
