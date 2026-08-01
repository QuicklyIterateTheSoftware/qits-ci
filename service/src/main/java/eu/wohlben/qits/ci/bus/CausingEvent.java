package eu.wohlben.qits.ci.bus;

import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Turns a run's recorded {@code triggerEventId} into the parent a publish is stamped with. One
 * implementation because both announcers need exactly this and a second copy of a defensive parse is
 * a copy that will be made less defensive.
 *
 * <p><b>This is where the platform's automatic causation edge is drawn.</b> The id comes off the
 * run's own row rather than out of {@code CausationScope}, because there is no ambient value left to
 * read: the engine consumed the frame on the bus's dispatch thread and the publish happens later on
 * {@code ci-run-worker}. An explicit non-null argument outranks the ambient context by design,
 * precisely for this case. A push passes null and publishes a root, which is correct — a push is not
 * caused by an event.
 */
final class CausingEvent {

  private static final Logger LOG = Logger.getLogger(CausingEvent.class);

  private CausingEvent() {}

  /**
   * The trigger event's id as a {@link UUID}, or null.
   *
   * <p>The column is a {@code varchar} holding a foreign id, so parsing is the boundary and it is
   * <b>defensive on purpose</b>: an id that will not parse must cost the run its causation edge and
   * nothing else. Throwing here would turn a malformed provenance value into a green run that
   * publishes no event at all — the announcement lost for the sake of the edge — which is strictly
   * worse than a root event plus a WARN naming the run. Unreachable through the engine, which only
   * ever writes a frame id qits-events minted; the WARN is what says so if it ever is not.
   */
  static UUID parentOf(String triggerEventId, String runId) {
    if (triggerEventId == null || triggerEventId.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(triggerEventId);
    } catch (IllegalArgumentException notAUuid) {
      LOG.warnf(
          "Run %s records trigger event id '%s', which is not a uuid — publishing a root event",
          runId, triggerEventId);
      return null;
    }
  }
}
