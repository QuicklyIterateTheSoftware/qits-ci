package eu.wohlben.qits.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The scope's hygiene, which is the whole of what it has to get right.
 *
 * <p>Every assertion here is about a thread being left as it was found. Both threads this runs on
 * in production are long-lived and reused — a websockets-next worker and, one repo layer up, a
 * single {@code ci-run-worker} that outlives every run in the process — so a value that leaked out
 * of a scope would not be a stale parent on one event, it would be a stale parent on every event
 * for as long as the process ran, with nothing anywhere to say so.
 *
 * <p>Plain JUnit. The class has no CDI in it and needs none: it is a static thread-local and the
 * two methods around it.
 */
class CausationScopeTest {

  private static final UUID OUTER = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID INNER = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @AfterEach
  void theTestThreadIsLeftClean() {
    assertNull(CausationScope.current(), "a test must not leak a cause onto the JUnit thread");
  }

  @Test
  void thereIsNoCauseUntilOneIsEstablished() {
    assertNull(CausationScope.current());
  }

  @Test
  void insideAScopeTheCauseIsTheScopesAndAfterwardsThereIsNoneAgain() {
    AtomicReference<UUID> inside = new AtomicReference<>();

    CausationScope.with(OUTER, () -> inside.set(CausationScope.current()));

    assertEquals(OUTER, inside.get());
    assertNull(CausationScope.current(), "the scope must not outlive its body");
  }

  /** Nesting: the innermost wins for its region, and the outer is intact the moment it ends. */
  @Test
  void anInnerScopeWinsForItsRegionAndTheOuterSurvivesIt() {
    AtomicReference<UUID> inInner = new AtomicReference<>();
    AtomicReference<UUID> afterInner = new AtomicReference<>();

    CausationScope.with(
        OUTER,
        () -> {
          CausationScope.with(INNER, () -> inInner.set(CausationScope.current()));
          afterInner.set(CausationScope.current());
        });

    assertEquals(INNER, inInner.get());
    assertEquals(OUTER, afterInner.get(), "restore-the-previous, not clear");
    assertNull(CausationScope.current());
  }

  /**
   * {@code with(null, …)} is the deliberate detach: it hides an enclosing cause from its body and
   * hands it back afterwards. A different sentence from {@code publish(event, null)}, which means
   * "no argument" and therefore falls back to whatever is ambient.
   */
  @Test
  void withNullDetachesInsideAndRestoresOutside() {
    AtomicReference<UUID> inDetached = new AtomicReference<>();
    AtomicReference<UUID> afterDetached = new AtomicReference<>();

    CausationScope.with(
        OUTER,
        () -> {
          CausationScope.with(null, () -> inDetached.set(CausationScope.current()));
          afterDetached.set(CausationScope.current());
        });

    assertNull(inDetached.get(), "in here, nothing is the cause");
    assertEquals(OUTER, afterDetached.get());
  }

  @Test
  void aBodyThatThrowsStillRestores() {
    RuntimeException boom = new RuntimeException("boom");

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                CausationScope.with(
                    OUTER,
                    () -> {
                      throw boom;
                    }));

    assertEquals(boom, thrown, "the body's exception must not be swallowed or wrapped");
    assertNull(CausationScope.current(), "finally, not a trailing statement");
  }

  @Test
  void aThrowFromAnInnerScopeLeavesTheOuterIntact() {
    AtomicReference<UUID> afterInner = new AtomicReference<>();

    CausationScope.with(
        OUTER,
        () -> {
          assertThrows(
              RuntimeException.class,
              () ->
                  CausationScope.with(
                      INNER,
                      () -> {
                        throw new RuntimeException("boom");
                      }));
          afterInner.set(CausationScope.current());
        });

    assertEquals(OUTER, afterInner.get());
  }

  /**
   * <b>The correctness half of "remove(), not set(null)".</b> A pooled thread runs unrelated tasks
   * one after another, and the second must not observe a cause the first established. Two tasks on
   * a single-threaded executor, the second reading {@code current()} after the first's scope has
   * unwound — and the assertion that they really are one thread, because otherwise this test passes
   * for the wrong reason.
   *
   * <p>The <em>housekeeping</em> half — that the entry is removed rather than nulled, so a pooled
   * thread carries no leftover map entry — is not observable from here at all: {@code
   * ThreadLocalMap} is not reachable and {@code set(null)} would read identically. It is asserted by
   * the implementation being one branch in one place, which is why {@code CausationScope} keeps its
   * private {@code set}.
   */
  @Test
  void aSecondTaskOnTheSameThreadSeesNothingTheFirstEstablished() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Long> firstThread =
          pool.submit(
              () -> {
                CausationScope.with(OUTER, () -> {});
                return Thread.currentThread().threadId();
              });
      Future<Long> secondThread = pool.submit(() -> Thread.currentThread().threadId());
      Future<UUID> seenBySecond = pool.submit(CausationScope::current);

      assertEquals(
          firstThread.get(),
          secondThread.get(),
          "the point of the test is that these are the SAME thread");
      assertNull(seenBySecond.get(), "an unrelated task must not inherit the previous one's cause");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * A plain {@link ThreadLocal}, not an {@code InheritableThreadLocal}: a thread started inside a
   * scope does not get the value. That is the decision, not an accident — inheritance copies at
   * thread <em>creation</em>, which pooled executors do long before any consumption, so it would buy
   * nothing where it matters while tainting any thread a listener spawned for another reason.
   */
  @Test
  void aFreshlyStartedThreadDoesNotInheritTheCause() throws Exception {
    AtomicReference<UUID> seenByChild = new AtomicReference<>(OUTER);

    CausationScope.with(
        OUTER,
        () -> {
          Thread child = new Thread(() -> seenByChild.set(CausationScope.current()));
          child.start();
          try {
            child.join();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });

    assertNull(seenByChild.get(), "propagation is explicit; nothing is inherited");
  }

  @Test
  void theSameScopeMayBeEnteredTwiceInSequence() {
    CausationScope.with(OUTER, () -> assertEquals(OUTER, CausationScope.current()));
    assertNull(CausationScope.current());
    CausationScope.with(INNER, () -> assertEquals(INNER, CausationScope.current()));
    assertNull(CausationScope.current());
  }
}
