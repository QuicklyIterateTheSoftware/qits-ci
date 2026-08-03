package eu.wohlben.qits.ci.entity;

/**
 * What a container probe found out about one candidate daemon version (the ladder,
 * ci-daemon-autoadopt-plan.md §2.3).
 *
 * <p>{@link #PROVEN} and {@link #REJECTED} are the only durable values. {@link #UNPROVEN} means no
 * probe has run yet; {@link #UNKNOWN} means one ran but could not complete (no docker, a
 * container-name collision, an unpullable probe image) -- a statement about the probe, not the
 * candidate, so it is never the answer a run pins on but it <em>is</em> eligible for a later retry,
 * exactly like {@link #UNPROVEN}. {@link #REJECTED} alone is terminal: a real daemon dialled in and
 * was found wanting on its own merits, and that verdict is never retried.
 */
public enum CiDaemonPinVerdict {
  UNPROVEN,
  PROVEN,
  REJECTED,
  UNKNOWN
}
