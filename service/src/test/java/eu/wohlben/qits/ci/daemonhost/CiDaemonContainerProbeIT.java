package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.control.DaemonProbe.ProbeResult;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link CiDaemonContainerProbe#probeUnconditionally}, once, against a real container -- the real
 * daemon binary registering is {@link Verdict#PROVEN}, and a binary url that 404s (a candidate this
 * store never got) never dials and is {@link Verdict#REJECTED} with a captured log. Everything
 * {@code CiDaemonContainerProbeTest} proves against {@link FakeCiDaemon} is proven once more here
 * with no in-JVM shortcut, the same split {@code CiDaemonHandshakeIT} draws for the step lifecycle
 * itself.
 *
 * <p>Hand-wires its own {@link CiDaemonContainerProbe} exactly as {@code CiDaemonHandshakeIT}
 * hand-wires its own {@link CiDaemonLauncher}: {@code LaunchMode.current()} is a JVM-wide read a
 * fresh instance cannot escape, so {@link CiDaemonContainerProbe#probe}'s test-mode guard is
 * bypassed by calling {@link CiDaemonContainerProbe#probeUnconditionally} directly rather than by
 * trying to make this process look like something other than a {@code @QuarkusTest}. The registry
 * is the injected bean -- it must be the one {@link CiDaemonSocket} dispatches to.
 *
 * <p>Carries the same three environmental hazards {@code CiDaemonHandshakeIT}'s class javadoc
 * documents in full (IPv4 bind, host-gateway warm-up, and -- unused here, since the probe never
 * clones -- the smart-HTTP fixture) and the same "fails rather than skips" caveat for a host with no
 * route back to this JVM. Run with {@code -DskipITs=false}; excluded from {@code -Dnative} like
 * every docker-backed IT here.
 */
@QuarkusTest
@Tag("extended")
public class CiDaemonContainerProbeIT {

  private static final String IMAGE = System.getProperty("qits.ci.step-image", "buildpack-deps:scm");

  private static final String RUNTIME = System.getProperty("qits.ci.container-runtime", "docker");

  /** Path to the binary qits-ci-daemon's native build produced. Absent means both cases skip. */
  private static final String BINARY = System.getProperty("qits.ci.daemon-binary");

  private static final String NETWORK = System.getProperty("qits.ci.network", "qits-net");

  @Inject CiDaemonRegistry registry;

  @TestHTTPResource("/ci/daemon")
  URI controlSocket;

  @Test
  public void aRealDaemonRegisteringWithTheHostsOwnCapabilityIsProven() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    assumeTrue(binaryAvailable(), "-Dqits.ci.daemon-binary=<path> required for this IT");

    withFixture(
        binaryUrl -> {
          ProbeResult result = probe(binaryUrl).probeUnconditionally("ignored");
          assertEquals(Verdict.PROVEN, result.verdict(), String.valueOf(result));
        });
  }

  @Test
  public void aBinaryUrlThatNeverRegistersIsRejectedWithItsLogCaptured() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");

    withFixture(
        binaryUrl -> {
          ProbeResult result = probe(binaryUrl + "-does-not-exist").probeUnconditionally("ignored");
          assertEquals(Verdict.REJECTED, result.verdict());
          assertTrue(
              result.detail().contains("could not fetch"),
              "expected the bootstrap's own report, got:\n" + result.detail());
        });
  }

  // --- fixture ------------------------------------------------------------------------------

  private interface ProbeCase {
    void run(String binaryUrl) throws Exception;
  }

  /**
   * A hand-wired probe against a hand-wired launcher, the same pairing {@code CiDaemonHandshakeIT}
   * builds -- config is per-test (the served port is not known until the fixture is listening), and
   * this IT is about the transport rather than the production wiring.
   */
  private CiDaemonContainerProbe probe(String binaryUrl) {
    CiDaemonLauncher launcher = new CiDaemonLauncher();
    launcher.runtime = RUNTIME;
    launcher.network = NETWORK;
    launcher.containerGitUrl = "http://host.docker.internal:1"; // never dialled -- no clone happens
    launcher.containerDaemonUrl =
        "ws://host.docker.internal:" + controlSocket.getPort() + controlSocket.getPath();
    launcher.daemonBinaryUrlTemplate = binaryUrl;
    launcher.registerTimeoutSeconds = 180;
    launcher.outputMaxChars = 65536;
    launcher.memoryLimit = "2g";
    launcher.pidsLimit = "1024";
    launcher.cpus = "2";
    launcher.ensureNetwork();

    CiDaemonContainerProbe probe = new CiDaemonContainerProbe();
    probe.registry = registry;
    probe.launcher = launcher;
    probe.probeImage = IMAGE;
    probe.registerTimeoutSeconds = 120;
    return probe;
  }

  private void withFixture(ProbeCase probeCase) throws Exception {
    Path work = Files.createTempDirectory("ci-daemon-probe-it");
    byte[] binary = BINARY == null ? new byte[0] : Files.readAllBytes(Path.of(BINARY));
    try (GitHttpBackend fixture = GitHttpBackend.start(work, binary)) {
      GitHttpBackend.awaitReachableFromAContainer(
          RUNTIME, IMAGE, NETWORK, fixture.port(), controlSocket.getPort());
      // {version} is never substituted -- the probe always resolves against this exact url, exactly
      // as the production template resolves against qits-artifacts' version-addressed route.
      probeCase.run(fixture.containerBinaryUrl());
    } finally {
      deleteRecursively(work);
    }
  }

  private boolean dockerAndImageAvailable() {
    try {
      return new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private boolean binaryAvailable() {
    return BINARY != null && Files.isRegularFile(Path.of(BINARY));
  }

  private static void deleteRecursively(Path root) throws Exception {
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
