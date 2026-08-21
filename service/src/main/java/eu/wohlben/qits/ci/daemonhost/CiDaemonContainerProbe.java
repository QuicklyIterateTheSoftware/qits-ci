package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.DaemonProbe;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The sole production {@link DaemonProbe} (ci-daemon-autoadopt-plan.md §2.3, workstream BW): a real
 * step container with no step. It reuses the exact machinery {@code CiDaemonStepRunner} drives a
 * real step through -- {@link CiDaemonRegistry#registerLaunch}, {@link CiDaemonLauncher#launch},
 * {@link CiDaemonRegistry#awaitRegistered}, {@link CiDaemonLauncher#destroyWithLogs} on failure,
 * {@link CiDaemonRegistry#awaitHello}, {@link CiDaemonRegistry#awaitAckConfirmed} and {@link
 * CiDaemonLauncher#reap} -- so probing a candidate exercises the same production download path a
 * real run would.
 *
 * <p><b>A verdict is a round trip, not a wave.</b> {@code Hello} proves the daemon can reach the
 * host; it says nothing about whether the host can reach the daemon, and a real run depends on
 * exactly that direction for everything from {@code Ack} onward, {@code RunStep} above all. So
 * {@link #awaitVerdict} does not settle {@link DaemonProbe.Verdict#PROVEN} at {@code Hello} the way
 * an earlier version of this class did: it also waits for {@link
 * eu.wohlben.qits.cidaemon.protocol.AckReceived}, the daemon's confirmation that the host's own
 * {@code Ack} arrived. A daemon that dials, says {@code Hello} and then never confirms is {@link
 * DaemonProbe.Verdict#REJECTED} at the same deadline as every other unproven case -- it looked
 * alive and was never actually listening.
 *
 * <p><b>What it deliberately does not do.</b> It never waits for {@code Initialized}: the daemon
 * clones only after it dials, and a probe has no repository to hand it, so {@link #PROBE_REPO_ID}
 * is syntactically valid (it passes {@code CiIdentifiers.requireRepoId}) but names nothing a clone
 * can resolve. The container is reaped the moment the verdict is in -- now strictly after {@code
 * AckReceived} or the deadline, never at {@code Hello} -- which is what kills that clone along with
 * it. It is not a host-side execution of the binary either -- the daemon still runs inside the
 * sandbox {@link CiDaemonLauncher#buildWorkloadSpec} asks for, exactly as a step's daemon does.
 *
 * <p><b>Skipped under {@code LaunchMode.TEST}</b>, the same posture {@link
 * CiDaemonLauncher#onStart} takes toward its own boot reap: an ordinary {@code @QuarkusTest} that
 * somehow reaches an {@code UNPROVEN} candidate (this module carries no {@code DaemonProbe} test
 * double of its own -- {@code ci}'s {@code FakeDaemonProbe} does not cross the module's test
 * classpath) must never ask a real orchestrator for a real container. {@link
 * #probeUnconditionally} is the same logic
 * with that guard removed, package-private so a hand-wired instance can drive it against a real
 * orchestrator precisely as {@code CiDaemonHandshakeIT} hand-wires a {@link CiDaemonLauncher} for
 * the same
 * reason -- {@code LaunchMode.current()} is a JVM-wide read that a fresh instance cannot escape, so
 * bypassing the guard is the only way an {@code extended}-tagged {@code @QuarkusTest} IT can prove
 * the real container path at all. {@link #awaitVerdict} is the verdict logic on its own, with no
 * launch step at all -- what {@code CiDaemonContainerProbeTest} drives against {@link
 * FakeCiDaemon} to prove {@code REJECTED} both ways with no docker in reach.
 */
@ApplicationScoped
public class CiDaemonContainerProbe implements DaemonProbe {

  /**
   * Syntactically valid ({@code CiIdentifiers.requireRepoId}) and never a real repository -- the
   * daemon's clone against it fails quietly after the container is already reaped, which is exactly
   * what a probe wants: it never waits to find out.
   */
  static final String PROBE_REPO_ID = "qits-ci-daemon-probe";

  static final String PROBE_BRANCH = "main";

  /** A syntactically valid sha ({@code CiIdentifiers.requireSha}) naming no real commit. */
  static final String PROBE_SHA = "0".repeat(40);

  @Inject CiDaemonRegistry registry;

  @Inject CiDaemonLauncher launcher;

  @ConfigProperty(name = "qits.ci.daemon-probe-image")
  String probeImage;

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  @Override
  public ProbeResult probe(String version) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return new ProbeResult(Verdict.UNKNOWN, "ci-daemon container probe is skipped under test mode");
    }
    return probeUnconditionally(version);
  }

  /** {@link #probe} with the test-mode guard removed -- see the class javadoc. */
  ProbeResult probeUnconditionally(String version) {
    // A bare UUID, not "daemon-probe-" + UUID: the old prefix made every probe's runId start with
    // the literal "daemon-p", so CiDaemonLauncher.containerName's blind 8-character substring named
    // every probe container the same thing -- the incident this fixes. A bare UUID's own first 8
    // characters are already high-entropy hex, and containerName no longer trusts a prefix alone
    // anyway (it also folds in a disambiguator over the whole runId) -- see its own javadoc.
    String runId = UUID.randomUUID().toString();
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch(runId, 0, null);
    String containerName = CiDaemonLauncher.containerName(runId, 0);
    try {
      CiDaemonLauncher.Launched launched =
          launcher.launch(
              new CiDaemonLauncher.LaunchSpec(
                  runId,
                  0,
                  // Id-addressed: the probe clones nothing, so the constant stands in for a
                  // repository rather than naming one, and there is no project to scope it to.
                  CiRepoRef.of(PROBE_REPO_ID),
                  PROBE_BRANCH,
                  PROBE_SHA,
                  probeImage,
                  credentials.daemonId(),
                  credentials.secret(),
                  launcher.resolveBinaryUrl(version),
                  // No step, so no step deadline: the register timeout plus the configured default
                  // is what bounds this container's lifetime, which is generous for a probe and is
                  // the point -- the reap below is what really ends it.
                  0,
                  // A probe never publishes, so it never asks for the socket.
                  false,
                  // And it runs no repository's script, so it takes the image's own user.
                  "",
                  Map.of()));
      if (!launched.started()) {
        // The orchestrator refused, or could not be reached, or could not start the container --
        // an unpullable probe image, a down daemon. The probe could not run at all, which is UNKNOWN
        // rather than a verdict about the candidate.
        return new ProbeResult(
            Verdict.UNKNOWN, "the probe container was not started: " + launched.error());
      }
      return awaitVerdict(credentials.daemonId(), containerName);
    } finally {
      registry.reap(credentials.daemonId());
      launcher.reap(containerName);
    }
  }

  /**
   * The verdict for an already-launched (or already-dialled, in a test) daemon: registered,
   * capability-matched, <em>and</em> confirmed the host's {@code Ack} is {@link Verdict#PROVEN};
   * anything else observable is {@link Verdict#REJECTED}. Package-private so {@code
   * CiDaemonContainerProbeTest} can drive it directly against {@link FakeCiDaemon} with no
   * container and no docker at all.
   *
   * <p>All three stages -- registration, {@code Hello}, and the {@code AckReceived} confirmation --
   * share one deadline rather than getting {@code registerTimeoutSeconds} apiece. A daemon that is
   * merely slow to dial must not also get a full fresh budget to confirm; the whole round trip is
   * one thing the probe is proving, and {@link #probeUnconditionally}'s reap happens only once this
   * method returns, so a shared deadline is also what keeps the container's total lifetime bounded
   * by the one configured number.
   */
  ProbeResult awaitVerdict(String daemonId, String containerName) {
    Instant deadline = Instant.now().plusSeconds(registerTimeoutSeconds);
    if (!registry.awaitRegistered(daemonId, remaining(deadline))) {
      // Never dialled -- the bootstrap's own stderr is the only account, and the removal is what
      // brings it back. The reap in probeUnconditionally's finally then finds nothing, which is a
      // success.
      return new ProbeResult(Verdict.REJECTED, launcher.destroyWithLogs(containerName));
    }
    // Registration completes at websocket admission, one round trip before the daemon has said
    // anything -- reading capabilityVersionOf() straight after would see -1/null for a daemon whose
    // Hello simply has not arrived yet. Wait on the Hello itself instead: a real daemon says Hello
    // immediately, so registered-but-silent within the shared deadline is a genuine REJECTED.
    Integer capabilityVersion = registry.awaitHello(daemonId, remaining(deadline));
    if (capabilityVersion == null || capabilityVersion != CiDaemonProtocol.CAPABILITY_VERSION) {
      return new ProbeResult(
          Verdict.REJECTED,
          "ci-daemon announced capability "
              + capabilityVersion
              + ", this host speaks "
              + CiDaemonProtocol.CAPABILITY_VERSION);
    }
    // Hello only proves daemon→host. The host's own Ack -- sent the moment Hello was processed,
    // above -- is host→daemon, exactly the direction a real run depends on for everything from here
    // on (RunStep not least). A daemon that never confirms it arrived looked alive and was never
    // actually listening.
    if (!registry.awaitAckConfirmed(daemonId, remaining(deadline))) {
      return new ProbeResult(
          Verdict.REJECTED, "ci-daemon never confirmed the host's Ack (no host→daemon proof)");
    }
    return new ProbeResult(Verdict.PROVEN, "");
  }

  /** What is left of {@code deadline}, clamped to non-negative so a slow stage cannot borrow time
   *  a later {@code await} would read as a longer budget than the probe actually has left. */
  private static Duration remaining(Instant deadline) {
    Duration left = Duration.between(Instant.now(), deadline);
    return left.isNegative() ? Duration.ZERO : left;
  }
}
