package eu.wohlben.qits.ci.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiEventSelection.Group;
import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
import eu.wohlben.qits.ci.control.CiEventSelection.PathCondition;

/**
 * Decides whether one event's payload satisfies one {@link CiEventSelection}. <b>Pure</b>: static,
 * stateless, no injection, no IO, and therefore exhaustively testable without a container — which is
 * the point, because this is the function that decides whether a repository's pipeline runs.
 *
 * <p>Groups OR, conditions within a group AND, matchers on one path AND. Evaluation
 * <b>short-circuits at the first matching group</b>: matching is boolean, not multiplicative, so a
 * {@code when:} whose groups all match still produces exactly one run. (The guarantee that survives
 * redelivery and races is not this short-circuit but the unique constraint on {@code
 * (trigger_event_id, repo_id, config_path)} — this one only keeps the arithmetic honest.)
 *
 * <p><b>Reading the payload needs no reflection</b>, which is why it is {@code readTree} and a
 * {@link JsonNode} walk rather than binding to anything. The lesson is written up in {@code
 * EventWireReflection}: binding a record in a native image needs metadata, an untyped tree read does
 * not. A trigger engine that could stop matching in the binary while every JVM test stayed green is
 * exactly the failure this repository has already paid for twice.
 */
public final class CiEventSelectionEvaluator {

  /**
   * Private and plain. It only ever calls {@code readTree}, so none of {@code CanonicalJson}'s
   * knobs apply — this mapper reads another service's bytes, it never writes any, and nothing about
   * a wire contract depends on it. It is not the CDI bean for the ordinary reason: an application's
   * {@code ObjectMapperCustomizer} must not be able to change what a trigger matches.
   */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private CiEventSelectionEvaluator() {}

  /**
   * Parses a canonical payload into a tree, or null when it is absent or not JSON at all. Null is a
   * payload with no paths in it: {@code exists: false} still holds over it and every other matcher
   * fails, which is the honest reading of "there was nothing to look in".
   */
  public static JsonNode parsePayload(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return MAPPER.readTree(payload);
    } catch (Exception notJson) {
      return null;
    }
  }

  /** Whether this selection accepts this payload. An unconditional selection accepts every one. */
  public static boolean matches(CiEventSelection selection, JsonNode payload) {
    if (selection == null || selection.isUnconditional()) {
      return true;
    }
    for (Group group : selection.groups()) {
      if (groupMatches(group, payload)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The path a trigger file writes when it means "this repository", and the field the platform
   * answers it with now.
   *
   * <p><b>{@code repoId} names the repository's ADDRESSABLE NAME, and the id is the legacy arm.</b>
   * After the identity cutover an {@code SCM*} event's {@code repoId} is an opaque storage UUID —
   * nothing a repository could write in a file, and nothing that stays the same across a
   * re-bootstrap — while the same event carries {@code repoName} filled from the address the push
   * arrived on. So a condition on {@code repoId} is evaluated against {@code repoName} whenever the
   * payload has one, and against {@code repoId} when it does not. The nine estate trigger files that
   * say {@code repoId: {exact: qits-blobstore}} therefore keep matching on both sides of the
   * cutover, unedited: before it, id and name agree and the id arm answers; after it, the name
   * field is there and answers instead.
   *
   * <p>It is an <b>alias at evaluation</b> rather than a rewrite at parse, because whether the
   * fallback applies is a property of the arriving event and not of the file. A payload carrying no
   * {@code repoName} — every non-SCM event on this bus — is unaffected in every direction.
   */
  static final String REPO_ID_PATH = "repoId";

  static final String REPO_NAME_PATH = "repoName";

  private static boolean groupMatches(Group group, JsonNode payload) {
    for (PathCondition condition : group.conditions()) {
      JsonNode at = resolveAddressable(payload, condition.path());
      for (Matcher matcher : condition.matchers()) {
        if (!matcherMatches(matcher, at)) {
          return false;
        }
      }
    }
    // An empty group asserts nothing and therefore holds. Unreachable through the parser, which
    // rejects an empty group as malformed — stated here so the function is total on its own terms.
    return true;
  }

  private static boolean matcherMatches(Matcher matcher, JsonNode at) {
    boolean present = at != null;
    if (matcher.kind() == Matcher.Kind.EXISTS) {
      return present == matcher.expected();
    }
    return present && matchesScalar(matcher, asString(at));
  }

  /**
   * One matcher against a value that is always there — the shape a <b>step's {@code branches:}</b>
   * filter has, where the subject is the run's branch rather than a path into a payload.
   *
   * <p>Public and here rather than private and duplicated: a platform with two implementations of
   * "{@code prefix} means starts-with" has one that will drift. {@code EXISTS} answers its own
   * {@code expected} because a present value exists; a branch filter never parses one (the parser
   * refuses it, since a matcher that can only say yes is a trap wearing a feature's name), so that
   * arm is reachable only from the payload side, which never calls this with it.
   */
  public static boolean matchesScalar(Matcher matcher, String value) {
    return switch (matcher.kind()) {
      case EXISTS -> matcher.expected();
      case EXACT -> value.equals(matcher.value());
      case PREFIX -> value.startsWith(matcher.value());
    };
  }

  /**
   * {@link #resolve}, with the one alias this platform's identity split needs: {@link #REPO_ID_PATH}
   * reads {@link #REPO_NAME_PATH} when the payload carries it. Everything else resolves literally.
   *
   * <p>Only the exact top-level path is aliased — never a prefix, never a nested {@code
   * something.repoId} — so an event that means a different thing by the word cannot be caught by it.
   */
  static JsonNode resolveAddressable(JsonNode root, String path) {
    if (REPO_ID_PATH.equals(path)) {
      JsonNode name = resolve(root, REPO_NAME_PATH);
      if (name != null) {
        return name;
      }
    }
    return resolve(root, path);
  }

  /**
   * Walks a dot-path into the payload. Navigation only — a segment indexes an object key and
   * nothing else, so an array or a scalar part-way down ends the walk with "not there".
   *
   * <p><b>A JSON {@code null} counts as absent.</b> The canonical form omits null fields entirely, so
   * this is unreachable through anything this platform publishes; the rule is stated rather than
   * left to Jackson's {@code NullNode} because "the field is there and its value is nothing" is not a
   * distinction a selection should have to make.
   */
  static JsonNode resolve(JsonNode root, String path) {
    if (root == null || path == null || path.isEmpty()) {
      return null;
    }
    JsonNode current = root;
    int from = 0;
    while (from <= path.length()) {
      int dot = path.indexOf('.', from);
      String segment = dot < 0 ? path.substring(from) : path.substring(from, dot);
      if (current == null || !current.isObject()) {
        return null;
      }
      current = current.get(segment);
      if (dot < 0) {
        break;
      }
      from = dot + 1;
    }
    return current == null || current.isNull() ? null : current;
  }

  /**
   * The value a matcher compares against: a JSON string is its own text, and anything else is its
   * JSON literal. So {@code exact: "3"} matches the number 3 and {@code exact: "true"} matches the
   * boolean — one comparison, spelled the way a repository already reads the payload in the event
   * log.
   */
  static String asString(JsonNode node) {
    return node.isTextual() ? node.textValue() : node.toString();
  }
}
