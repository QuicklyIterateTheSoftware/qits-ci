package eu.wohlben.qits.ci.control;

/**
 * "Start this version and tell me what happened" -- the container probe port
 * (ci-daemon-autoadopt-plan.md §2.3). {@link CiDaemonPins} calls it to turn an {@code UNPROVEN}
 * candidate into a verdict: a real step container with no step, launched, waited on for the dial,
 * read for its reported capability version, then reaped.
 *
 * <p>An interface rather than a call so {@code ci} stays free of the docker vocabulary and the web
 * stack that launching a container needs -- the same reason {@link RunAnnouncer} is a port here and
 * a bus client in {@code service}. <b>Zero implementations is a supported configuration</b>, the
 * same precedent: this port lands before its sole production implementation does
 * ({@code service/…/daemonhost/CiDaemonContainerProbe}), and a deployment with no implementation
 * probes nothing -- every candidate stays {@code UNPROVEN} forever and the ladder never rises above
 * the configured pin, which is exactly the status quo this feature must not disturb before it is
 * whole.
 */
public interface DaemonProbe {

  /** Mirrors {@link eu.wohlben.qits.ci.entity.CiDaemonPinVerdict}, minus the non-terminal {@code
   *  UNPROVEN} a probe result can never answer. */
  enum Verdict {
    PROVEN,
    REJECTED,
    UNKNOWN
  }

  /** {@code detail} is the docker logs tail for a {@link Verdict#REJECTED} probe, or the reason for
   *  a {@link Verdict#UNKNOWN} one; blank for {@link Verdict#PROVEN}. */
  record ProbeResult(Verdict verdict, String detail) {}

  /**
   * Probes one daemon version. Must never throw for an ordinary failure -- a container that never
   * dialled or a capability mismatch is {@link Verdict#REJECTED}, not an exception; an
   * implementation reserves throwing for a bug in itself, which the caller treats as {@link
   * Verdict#UNKNOWN} rather than letting it escape.
   */
  ProbeResult probe(String version);
}
