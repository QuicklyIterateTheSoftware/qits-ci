package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every rule {@link VersionSort}'s javadoc states, held. Plain JUnit and no container, which the
 * comparator's purity is what buys — and it is worth having exhaustively, because this function
 * decides which of a push's tags gets built and the wrong answer is silent.
 *
 * <p>The cases are written as "older, newer" pairs and each is asserted <b>in both directions</b>:
 * an ordering that says a &lt; b and b &lt; a is not an ordering, and a comparator that returns a
 * constant sign would pass half of these.
 */
public class VersionSortTest {

  /** Asserts the pair orders this way round, the other way round, and reflexively. */
  private static void older(String older, String newer) {
    assertTrue(VersionSort.compare(older, newer) < 0, older + " should precede " + newer);
    assertTrue(VersionSort.compare(newer, older) > 0, newer + " should follow " + older);
    assertEquals(0, VersionSort.compare(older, older), older + " equals itself");
    assertEquals(0, VersionSort.compare(newer, newer), newer + " equals itself");
  }

  @Test
  public void aDigitRunComparesAsANumberAndNotAsText() {
    // The case the platform actually has: CalVer, where the last segment's WIDTH varies. Plain
    // string order puts 184518 before 98 and would build the wrong tag of a release push.
    older("2026.810.98", "2026.810.184518");
    older("2026.810.184518", "2026.811.1");
    older("2026.9.1", "2026.10.1");
    older("1.9", "1.10");
    older("v1.2.9", "v1.2.10");
  }

  @Test
  public void aNumberLongerThanALongStillOrders() {
    // Written by hand rather than through parseLong precisely so this is an ordering rather than an
    // overflow. Same digit count, so it is the digit-by-digit arm; then one digit longer.
    older("1." + "9".repeat(25), "1." + "9".repeat(24) + "99");
    older("1." + "9".repeat(25), "1." + "1".repeat(26));
  }

  @Test
  public void leadingZerosOnlyBreakATie() {
    older("1.01", "1.1");
    older("1.001", "1.01");
    older("1.09", "1.10");
    // Not a tie at all: the zeros are dropped before the numbers are compared.
    older("1.02", "1.3");
    assertEquals(0, VersionSort.compare("1.0", "1.0"));
    assertEquals(0, VersionSort.compare("1.00", "1.00"));
  }

  @Test
  public void aPrefixIsTheSmallerString() {
    older("1.0", "1.0.1");
    older("1.0", "1.0-rc1");
    older("", "0");
    older("v", "v1");
  }

  @Test
  public void aDigitPrecedesANonDigitAtTheSamePosition() {
    older("1.9", "1.x");
    older("release-1", "release-x");
  }

  @Test
  public void nonNumericSegmentsCompareAsText() {
    older("1.0-alpha", "1.0-beta");
    older("qits-a", "qits-b");
    // Code points, so an uppercase letter precedes a lowercase one. Stated rather than relied on:
    // it is what String order already does and no tag scheme here mixes the two.
    older("1.0-B", "1.0-a");
  }

  @Test
  public void aTagWithNoDigitsInItIsStillOrdered() {
    older("latest", "stable");
    assertEquals(0, VersionSort.compare("latest", "latest"));
  }

  @Test
  public void theComparatorSortsAWholeReleasePushIntoOrder() {
    // What a multi-tag push looks like, shuffled, and the answer the supersede reads off the end.
    List<String> tags =
        List.of("2026.810.98", "2026.811.1", "2026.810.184518", "2026.79.240000", "2026.811.10")
            .stream()
            .sorted(VersionSort.COMPARATOR)
            .toList();
    assertEquals(
        List.of("2026.79.240000", "2026.810.98", "2026.810.184518", "2026.811.1", "2026.811.10"),
        tags);
  }

  @Test
  public void aNullIsRefusedRatherThanTreatedAsTheOldestVersion() {
    // "No version" is a failure to compare, and a comparator that answered it would let an
    // unreadable payload supersede a real build. The caller has to decide, and it does.
    assertThrows(NullPointerException.class, () -> VersionSort.compare(null, "1.0"));
    assertThrows(NullPointerException.class, () -> VersionSort.compare("1.0", null));
  }
}
