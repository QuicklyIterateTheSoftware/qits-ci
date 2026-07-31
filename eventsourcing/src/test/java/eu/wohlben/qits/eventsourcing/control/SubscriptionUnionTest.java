package eu.wohlben.qits.eventsourcing.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.eventsourcing.QitsRawEventListener;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What goes in the subscribe frame, as arithmetic rather than as a socket.
 *
 * <p>The derivation is one small function and it decides what an entire deployment hears, so it is
 * worth exhausting here — every shape of "who contributed what" in milliseconds — and asserting once
 * on the wire, in {@code EventStreamSubscriberTest}, that the function is the one actually used. A
 * container is not what makes a union test true.
 */
class SubscriptionUnionTest {

  @Test
  void typedOnlyIsTheTypedSignaturesSorted() {
    assertEquals(
        List.of("Alpha", "Beta", "Gamma"),
        EventDispatcher.union(ordered("Gamma", "Alpha", "Beta"), List.of()));
  }

  @Test
  void rawOnlyIsWhatTheRawListenersWantSorted() {
    assertEquals(
        List.of("Alpha", "Beta", "Gamma"),
        EventDispatcher.union(Set.of(), List.of(Set.of("Gamma", "Alpha"), Set.of("Beta"))));
  }

  /** A signature both seams want is one entry: the wire set is a set. */
  @Test
  void mixedIsTheUnionWithNoDuplicates() {
    assertEquals(
        List.of("Alpha", "Beta", "Gamma"),
        EventDispatcher.union(ordered("Beta", "Alpha"), List.of(Set.of("Gamma", "Beta"))));
  }

  @Test
  void nobodyWantingAnythingIsAnEmptySetAndThereforeNoStream() {
    assertEquals(List.of(), EventDispatcher.union(Set.of(), List.of()));
    assertEquals(List.of(), EventDispatcher.union(Set.of(), List.of(Set.of(), Set.of())));
  }

  /**
   * <b>The collapse.</b> One listener asking for everything makes narrowing the wire pointless, so
   * the frame stops naming names — and it must be exactly {@code ["*"]} rather than {@code ["*"]}
   * plus the rest, because qits-events reads that list as the subscription set and a stray sibling
   * would be a name nobody needs to see spelled out.
   */
  @Test
  void anyStarCollapsesTheWholeUnionToJustStar() {
    assertEquals(
        List.of("*"),
        EventDispatcher.union(ordered("Alpha", "Beta"), List.of(Set.of(QitsRawEventListener.ALL))));
    assertEquals(
        List.of("*"),
        EventDispatcher.union(
            ordered("Alpha"), List.of(Set.of("Gamma"), Set.of("Beta", QitsRawEventListener.ALL))));
    assertEquals(
        List.of("*"), EventDispatcher.union(Set.of(), List.of(Set.of(QitsRawEventListener.ALL))));
  }

  /**
   * A raw listener answers at runtime, so its set is not a compile-time literal and can contain
   * junk. Junk that reached the frame would be a subscription qits-events could not read — a whole
   * deployment deaf because one listener returned a set with a null in it — so it is dropped here.
   */
  @Test
  void nullAndBlankEntriesAreDroppedRatherThanSent() {
    Collection<String> withJunk = new LinkedHashSet<>(Arrays.asList("Alpha", null, "", "   "));

    assertEquals(List.of("Alpha"), EventDispatcher.union(Set.of(), List.of(withJunk)));
  }

  /** A listener that answers null at all is treated as one that wants nothing. */
  @Test
  void aNullSetContributesNothing() {
    assertEquals(
        List.of("Alpha"), EventDispatcher.union(ordered("Alpha"), Arrays.asList((Set<String>) null)));
  }

  /** Insertion-ordered on purpose, so a sorted result cannot be the input order in disguise. */
  private static Collection<String> ordered(String... signatures) {
    return new LinkedHashSet<>(Arrays.asList(signatures));
  }
}
