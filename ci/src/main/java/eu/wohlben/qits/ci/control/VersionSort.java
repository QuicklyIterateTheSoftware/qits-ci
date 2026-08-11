package eu.wohlben.qits.ci.control;

import java.util.Comparator;
import java.util.Objects;

/**
 * Orders two version-shaped strings the way GNU version sort does: <b>a run of digits compares as a
 * number, everything else compares as text</b>. So {@code 2026.810.184518} is newer than {@code
 * 2026.810.98}, which plain string order gets backwards — and getting it backwards is a release
 * train that builds the wrong tag.
 *
 * <p><b>Hand-rolled rather than depended on</b>, for the reason every client in this repository is:
 * a dependency is a decision about what the native-image builder has to be told, and forty lines of
 * {@code char} arithmetic need told nothing. It is also pure — static, stateless, no IO — so it is
 * exhaustively testable without a container, exactly like {@link CiEventSelectionEvaluator}.
 *
 * <h2>The rules, stated rather than implied</h2>
 *
 * <ul>
 *   <li><b>Digit runs compare numerically</b>, of unbounded length: the shorter run after leading
 *       zeros are dropped is the smaller number, and same-length runs compare digit by digit. No
 *       {@code parseLong}, so a version longer than 19 digits is ordered rather than refused.
 *   <li><b>Leading zeros break a numeric tie</b>, more zeros first ({@code 1.01} before {@code
 *       1.1}). That is GNU's reading of a leading zero as a fraction, arrived at by a cheaper route
 *       that agrees with it wherever a tie is what is left.
 *   <li><b>A digit sorts before a non-digit</b> at the same position, so {@code 1.9} precedes {@code
 *       1.x}.
 *   <li><b>Everything else compares by code point</b>, and a string that is a prefix of the other is
 *       the smaller one — so {@code 1.0} precedes both {@code 1.0.1} and {@code 1.0-rc1}. This is
 *       the one place GNU is richer: it reads {@code ~} as sorting before an empty segment, which
 *       nothing this platform tags with uses.
 * </ul>
 *
 * <p>The platform's own tags are CalVer ({@code 2026.810.184518}), where every one of those rules
 * beyond the first is inert. They are written down because the comparator is offered a repository's
 * tag names, and a repository tags however it likes.
 */
public final class VersionSort {

  /** The same order as {@link #compare}, for a {@code sort} or a {@code max}. */
  public static final Comparator<String> COMPARATOR = VersionSort::compare;

  private VersionSort() {}

  /**
   * Negative when {@code a} is the older version, positive when it is the newer, zero when the two
   * strings are equal. Null-hostile: "no version" is a question a caller has to answer before it
   * gets here, and answering it silently as "the oldest" would supersede a run over a payload that
   * simply could not be read.
   */
  public static int compare(String a, String b) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      boolean digitA = isDigit(a.charAt(i));
      boolean digitB = isDigit(b.charAt(j));
      if (digitA && digitB) {
        int endA = endOfDigits(a, i);
        int endB = endOfDigits(b, j);
        int numeric = compareNumeric(a, i, endA, b, j, endB);
        if (numeric != 0) {
          return numeric;
        }
        i = endA;
        j = endB;
      } else if (digitA != digitB) {
        return digitA ? -1 : 1;
      } else if (a.charAt(i) != b.charAt(j)) {
        return Character.compare(a.charAt(i), b.charAt(j));
      } else {
        i++;
        j++;
      }
    }
    // One ran out: what is left of the other decides, so a prefix is the smaller string.
    return Integer.compare(a.length() - i, b.length() - j);
  }

  /** Compares two digit runs as numbers, with the leading-zero tiebreak. */
  private static int compareNumeric(String a, int fromA, int toA, String b, int fromB, int toB) {
    int startA = afterLeadingZeros(a, fromA, toA);
    int startB = afterLeadingZeros(b, fromB, toB);
    int digitsA = toA - startA;
    int digitsB = toB - startB;
    if (digitsA != digitsB) {
      return Integer.compare(digitsA, digitsB);
    }
    for (int k = 0; k < digitsA; k++) {
      char da = a.charAt(startA + k);
      char db = b.charAt(startB + k);
      if (da != db) {
        return Character.compare(da, db);
      }
    }
    // Equal numbers: the one written with more leading zeros is the smaller one.
    return Integer.compare(startB - fromB, startA - fromA);
  }

  /** The index of the first digit that is not a leading zero, or {@code to} for a run of zeros. */
  private static int afterLeadingZeros(String s, int from, int to) {
    int at = from;
    while (at < to - 1 && s.charAt(at) == '0') {
      at++;
    }
    return at;
  }

  private static int endOfDigits(String s, int from) {
    int at = from;
    while (at < s.length() && isDigit(s.charAt(at))) {
      at++;
    }
    return at;
  }

  /**
   * ASCII digits only. {@link Character#isDigit} would also accept the Unicode decimal digits, which
   * no version string uses and which {@link #compareNumeric}'s arithmetic would then read as
   * ordinary code points — an ordering that is wrong rather than merely surprising.
   */
  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
