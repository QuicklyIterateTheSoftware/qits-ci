package eu.wohlben.qits.eventsourcing;

import java.util.UUID;

/**
 * The ambient cause on this thread: the id of the event that a region of code is running
 * <em>because of</em>. {@link QitsEventBus#publish(QitsEvent)} reads it and stamps the envelope's
 * {@code parentId} with it, so a listener that publishes while consuming records the edge without
 * anybody passing an argument.
 *
 * <p><b>Why this is public API rather than an internal detail of the dispatcher.</b> {@link
 * QitsEventListener} tells implementors that "anything slow belongs on the listener's own
 * executor", and a hand-off to an executor is exactly what drops the ambient value — the scope the
 * dispatcher established belongs to the dispatch thread and nothing carries it across. Advice the
 * library gives has to come with the bridge that makes it safe:
 *
 * <pre>{@code
 * public void onEvent(ThingHappened event) {
 *   UUID cause = CausationScope.current();          // captured on the dispatch thread
 *   executor.submit(() -> CausationScope.with(cause, () -> {
 *     bus.publish(new SomethingFollowed(...));      // stamped with `cause`
 *   }));
 * }
 * }</pre>
 *
 * <p>Four properties, each of which is a decision:
 *
 * <ul>
 *   <li><b>Restore the previous value, never clear unconditionally.</b> {@link #with} saves what was
 *       there, sets the new value, and in a {@code finally} puts the old one back — {@code set} when
 *       there was one, {@link ThreadLocal#remove()} when there was not. Restoring is what makes
 *       nesting work; {@code remove()} rather than {@code set(null)} is what keeps a pooled worker
 *       from carrying an empty map entry and, more to the point, makes it impossible for the next,
 *       unrelated task on that thread to observe a stale cause. Both threads this runs on are
 *       long-lived and reused, so a leak here would attach one stale parent to every event for the
 *       life of the process.
 *   <li><b>Nested scopes: the innermost wins for its region and the outer is intact afterwards.</b>
 *       No merging and no stack of causes — an event has one parent.
 *   <li><b>A body that throws still restores.</b> Hence {@code finally} rather than a trailing
 *       statement.
 *   <li><b>A plain {@link ThreadLocal}, deliberately not an {@code InheritableThreadLocal}.</b>
 *       Inheritance copies the value at thread <em>creation</em>, and pooled executors — the only
 *       kind used here — create their threads long before any consumption happens, so it would buy
 *       nothing for the case that matters while silently tainting any thread a listener happened to
 *       spawn for an unrelated reason. Explicit propagation is the honest shape, and it is the
 *       snippet above.
 * </ul>
 *
 * <p><b>{@code with(null, body)} is the deliberate detach</b> — "in here, nothing is the cause" —
 * and it is a different sentence from {@code publish(event, null)}, which means "I have no argument
 * to pass" and therefore falls back to whatever is ambient. The asymmetry is real and was chosen so
 * that {@code publish} has exactly one call shape.
 *
 * <p>Automatic propagation across executors, reactive pipelines and Vert.x duplicated contexts is
 * out of scope: each would make the hand-off invisible for one framework's threads and absent for
 * the rest. This class <em>is</em> the extension point — the additive shape a later feature would
 * take is {@code wrap(Runnable)} / {@code wrap(Executor)}, capturing {@link #current()} at submit
 * time and re-establishing it at run time — and it needs no wire change on the day it is wanted.
 */
public final class CausationScope {

  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private CausationScope() {}

  /**
   * The ambient cause on this thread, or {@code null} when there is none. What {@link QitsEventBus}
   * stamps with, and what a listener captures before handing work to its own executor.
   */
  public static UUID current() {
    return CURRENT.get();
  }

  /**
   * Run {@code body} with {@code parentEventId} as the ambient cause, and leave the thread exactly
   * as it was found — whether the body returns or throws.
   *
   * @param parentEventId the cause for this region, or {@code null} to declare that nothing in here
   *     has one (the deliberate detach, which also hides an enclosing scope from the body)
   * @param body what runs under it
   */
  public static void with(UUID parentEventId, Runnable body) {
    UUID previous = CURRENT.get();
    set(parentEventId);
    try {
      body.run();
    } finally {
      set(previous);
    }
  }

  /** Null is an absence, not a value: {@code remove()} so a pooled thread carries no entry. */
  private static void set(UUID value) {
    if (value == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(value);
    }
  }
}
