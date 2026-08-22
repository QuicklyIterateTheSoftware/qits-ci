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
   * The paths a trigger file writes when it means "this repository", and the fields the platform
   * answers them with now.
   *
   * <p><b>They name the repository's ADDRESSABLE NAME, and the id is the legacy arm.</b> After the
   * identity cutover a repository's id is an opaque storage UUID — nothing a repository could write
   * in a file, and nothing that stays the same across a re-bootstrap — while the same event carries
   * the name filled from the address the push or the release arrived on. So a condition on an id
   * path is evaluated against its name field whenever the payload has one, and against the id field
   * when it does not. The estate's trigger files therefore keep matching on both sides of the
   * cutover, unedited: before it, id and name agree and the id arm answers; after it, the name field
   * is there and answers instead.
   *
   * <p><b>Two producers spell the pair differently, so there are two aliases rather than one.</b> An
   * {@code SCM*} event out of qits-githost says {@code repoId}/{@code repoName}; an {@code
   * SCMRelease} out of qits-workspaces says {@code repository}/{@code repositoryName}. Neither
   * spelling is this service's to choose, and a release pipeline selecting {@code repository:
   * {exact: qits-blobstore}} is exactly as entitled to keep working as a push pipeline selecting
   * {@code repoId:}. Measured on <b>2026-08-22</b>: five {@code SCMRelease} events on the bus, every
   * repository's {@code ci-event-release.yml} selecting the name, {@code repository} resolving
   * literally to the UUID, and not one release pipeline run since the re-bootstrap.
   *
   * <p>It is an <b>alias at evaluation</b> rather than a rewrite at parse, because whether the
   * fallback applies is a property of the arriving event and not of the file. A payload carrying no
   * name field is unaffected in every direction — including a payload whose {@code repository} is an
   * object rather than a repository, which resolves literally and can still be walked into.
   */
  static final String REPO_ID_PATH = "repoId";

  static final String REPO_NAME_PATH = "repoName";

  /** The same pair, as qits-workspaces spells it on an {@code SCMRelease}. */
  static final String REPOSITORY_PATH = "repository";

  static final String REPOSITORY_NAME_PATH = "repositoryName";

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
   * {@link #resolve}, with the aliases this platform's identity split needs: an id path reads its
   * name field when the payload carries one. Everything else resolves literally.
   *
   * <p>Only the exact top-level path is aliased — never a prefix, never a nested {@code
   * something.repoId} or {@code x.repository} — so an event that means a different thing by the word
   * cannot be caught by it. That restriction is what lets {@code repository} be an alias here and
   * still be an ordinary object to walk into elsewhere: {@code repository.url} is untouched.
   */
  static JsonNode resolveAddressable(JsonNode root, String path) {
    String nameField = nameFieldFor(path);
    if (nameField != null) {
      JsonNode name = resolve(root, nameField);
      if (name != null) {
        return name;
      }
    }
    return resolve(root, path);
  }

  /**
   * The name field a top-level id path is read through, or null when the path is not one of them.
   * Spelled as a switch over the two producers rather than a map, because the whole list is two
   * entries and adding a third is a decision about the DSL.
   */
  private static String nameFieldFor(String path) {
    if (REPO_ID_PATH.equals(path)) {
      return REPO_NAME_PATH;
    }
    if (REPOSITORY_PATH.equals(path)) {
      return REPOSITORY_NAME_PATH;
    }
    return null;
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
