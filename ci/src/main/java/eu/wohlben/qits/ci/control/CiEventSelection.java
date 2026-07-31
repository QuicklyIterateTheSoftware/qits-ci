package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * The parsed {@code when:} of a {@code .config/qits/ci-event-*.yml} — the selection a repository
 * declares over the payload of the event it named. A <b>data-only matcher document</b>: it is
 * evaluated by {@link CiEventSelectionEvaluator} and never interpreted, never templated, and never
 * handed to a shell.
 *
 * <p><b>That is the decision rather than a style.</b> The evaluation input is a payload authored by
 * another service and reaching qits-ci through an unauthenticated intake; interpolating it into a
 * shell "just for a true/false" would be remote influence over {@code sh -c} on the CI host, which
 * is the class of surface this repository has carefully avoided everywhere else (see {@code
 * CiIdentifiers} and {@code CiDaemonLauncher.BOOTSTRAP}). Bash stays what it already is here: the
 * <em>step</em> language, running in a step container after the trigger has decided.
 *
 * <h2>The shape, and why it is this one</h2>
 *
 * <ul>
 *   <li>{@code when:} is a <b>list of groups</b>, and groups are <b>OR</b>'d.
 *   <li>A group is a <b>map of dot-path → matcher</b>, and its entries are <b>AND</b>'d.
 *   <li>A map value may be a <b>list of matchers</b>, also AND'd — the one thing a plain map cannot
 *       express is two matchers on the same path in one group, and this restores it with no second
 *       nesting form.
 * </ul>
 *
 * <p>So "fire if any group fully matches" covers x AND y (one group, two entries) and x OR y (two
 * groups) without a structural choice at every level. An <b>absent or empty</b> {@code when:} is
 * {@link #unconditional()}: the trigger fires for every event of the name it declared. That is the
 * documented meaning and it is deliberate — a repository writing only {@code event:} has said
 * something complete, and reading it as "matches nothing" would make the commonest trigger the one
 * that silently never fires.
 *
 * <p>Paths are <b>navigation only</b>: dots between object keys, no wildcards, no filters, no
 * indexing. Every payload this platform emits is a flat-to-shallow canonical JSON object, and a
 * dot-path over one is a small loop rather than a dependency. {@code event:} matches the envelope's
 * {@code name} exactly and is not part of a selection.
 */
public record CiEventSelection(List<Group> groups) {

  /** One OR-branch: every condition in it must hold. */
  public record Group(List<PathCondition> conditions) {}

  /** One dot-path and everything asserted about it in this group — all of it must hold. */
  public record PathCondition(String path, List<Matcher> matchers) {}

  /**
   * The whole matcher vocabulary: {@code exact}, {@code prefix}, {@code exists}. Nothing else in v1,
   * and {@code regex} is deliberately absent until a real trigger needs it — {@code exact} and
   * {@code prefix} cover the release train, and a regex invites exactly the complexity a data-only
   * document exists to avoid.
   *
   * <p>Values compare as <b>strings</b>. The canonical payload is JSON, so a non-string value
   * compares by its JSON literal ({@code 3}, {@code true}, {@code ["a"]}) — which is what a repository
   * sees when it reads the event log, and therefore what it can write down without guessing at a
   * coercion table.
   */
  public record Matcher(Kind kind, String value, boolean expected) {

    public enum Kind {
      /** The value at the path equals this string exactly. A missing path fails. */
      EXACT,
      /** The value at the path starts with this string. A missing path fails. */
      PREFIX,
      /** Whether the path resolves at all. {@code exists: false} is how a repo asserts absence. */
      EXISTS
    }

    public static Matcher exact(String value) {
      return new Matcher(Kind.EXACT, value, true);
    }

    public static Matcher prefix(String value) {
      return new Matcher(Kind.PREFIX, value, true);
    }

    public static Matcher exists(boolean expected) {
      return new Matcher(Kind.EXISTS, null, expected);
    }
  }

  /** No groups: every event of the declared name matches. See the class javadoc. */
  public static CiEventSelection unconditional() {
    return new CiEventSelection(List.of());
  }

  public boolean isUnconditional() {
    return groups.isEmpty();
  }
}
