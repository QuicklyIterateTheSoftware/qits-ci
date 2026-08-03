package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.DaemonProbe;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonProtocol;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The sole production {@link DaemonProbe} (ci-daemon-autoadopt-plan.md §2.3, workstream BW): a real
 * step container with no step. It reuses the exact machinery {@code CiDaemonStepRunner} drives a
 * real step through -- {@link CiDaemonRegistry#registerLaunch}, {@link CiDaemonLauncher#launch},
 * {@link CiDaemonRegistry#awaitRegistered}, {@link CiDaemonLauncher#logs} on failure, {@link
 * CiDaemonRegistry#awaitHello} and {@link CiDaemonLauncher#reap} -- so probing a candidate
 * exercises the same production download path a real run would.
 *
 * <p><b>What it deliberately does not do.</b> It never waits for {@code Initialized}: the daemon
 * clones only after it dials, and a probe has no repository to hand it, so {@link #PROBE_REPO_ID}
 * is syntactically valid (it passes {@code CiIdentifiers.requireRepoId}) but names nothing a clone
 * can resolve. The container is reaped the moment the verdict is in, which is what kills that clone
 * along with it. It is not a host-side execution of the binary either -- the daemon still runs
 * inside the sandbox {@link CiDaemonLauncher#buildArgv} builds, exactly as a step's daemon does.
 *
 * <p><b>Skipped under {@code LaunchMode.TEST}</b>, the same posture {@link
 * CiDaemonLauncher#onStart} takes toward its own docker work: an ordinary {@code @QuarkusTest} that
 * somehow reaches an {@code UNPROVEN} candidate (this module carries no {@code DaemonProbe} test
 * double of its own -- {@code ci}'s {@code FakeDaemonProbe} does not cross the module's test
 * classpath) must never shell out to real docker. {@link #probeUnconditionally} is the same logic
 * with that guard removed, package-private so a hand-wired instance can drive it against real
 * docker precisely as {@code CiDaemonHandshakeIT} hand-wires a {@link CiDaemonLauncher} for the same
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
    String runId = "daemon-probe-" + UUID.randomUUID();
    CiDaemonRegistry.Credentials credentials = registry.registerLaunch(runId, 0, null);
    String containerName = CiDaemonLauncher.containerName(runId, 0);
    try {
      CiDaemonLauncher.Launched launched =
          launcher.launch(
              new CiDaemonLauncher.LaunchSpec(
                  runId,
                  0,
                  PROBE_REPO_ID,
                  PROBE_BRANCH,
                  PROBE_SHA,
                  probeImage,
                  credentials.daemonId(),
                  credentials.secret(),
                  launcher.resolveBinaryUrl(version),
                  false,
                  Map.of()));
      if (!launched.started()) {
        // Docker itself refused -- no docker, an unpullable probe image. The probe could not run at
        // all, which is UNKNOWN rather than a verdict about the candidate.
        return new ProbeResult(
            Verdict.UNKNOWN, "docker refused the probe container: " + launched.error());
      }
      return awaitVerdict(credentials.daemonId(), containerName);
    } finally {
      registry.reap(credentials.daemonId());
      launcher.reap(containerName);
    }
  }

  /**
   * The verdict for an already-launched (or already-dialled, in a test) daemon: registered and
   * capability-matched is {@link Verdict#PROVEN}; anything else observable is {@link
   * Verdict#REJECTED}. Package-private so {@code CiDaemonContainerProbeTest} can drive it directly
   * against {@link FakeCiDaemon} with no container and no docker at all.
   */
  ProbeResult awaitVerdict(String daemonId, String containerName) {
    Duration deadline = Duration.ofSeconds(registerTimeoutSeconds);
    if (!registry.awaitRegistered(daemonId, deadline)) {
      // Never dialled -- the bootstrap's own stderr is the only account, captured before the reap.
      return new ProbeResult(Verdict.REJECTED, launcher.logs(containerName));
    }
    // Registration completes at websocket admission, one round trip before the daemon has said
    // anything -- reading capabilityVersionOf() straight after would see -1/null for a daemon whose
    // Hello simply has not arrived yet. Wait on the Hello itself instead, with the same deadline: a
    // real daemon says Hello immediately, so registered-but-silent within it is a genuine REJECTED.
    Integer capabilityVersion = registry.awaitHello(daemonId, deadline);
    if (capabilityVersion != null && capabilityVersion == CiDaemonProtocol.CAPABILITY_VERSION) {
      return new ProbeResult(Verdict.PROVEN, "");
    }
    return new ProbeResult(
        Verdict.REJECTED,
        "ci-daemon announced capability "
            + capabilityVersion
            + ", this host speaks "
            + CiDaemonProtocol.CAPABILITY_VERSION);
  }
}
