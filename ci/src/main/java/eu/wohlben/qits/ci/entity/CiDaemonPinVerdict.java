package eu.wohlben.qits.ci.entity;

/**
 * What a container probe found out about one candidate daemon version (the ladder,
 * ci-daemon-autoadopt-plan.md §2.3).
 *
 * <p>{@link #UNPROVEN} is the only non-terminal value: every other value is durable, so a version is
 * probed at most once. {@link #UNKNOWN} is never the answer a run pins on -- it means the probe
 * itself could not run (no docker, an unpullable probe image), so the candidate is skipped exactly
 * like {@link #REJECTED} rather than trusted.
 */
public enum CiDaemonPinVerdict {
  UNPROVEN,
  PROVEN,
  REJECTED,
  UNKNOWN
}
