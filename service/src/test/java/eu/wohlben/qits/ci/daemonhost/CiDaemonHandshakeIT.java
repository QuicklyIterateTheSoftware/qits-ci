package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>The phase-B gate.</b> Everything the docker-free suite proves against {@link FakeCiDaemon}, once
 * more against a real container: a real image, a real download of a real daemon binary, a real dial
 * back over the network, and a real step whose output arrives as chunks. It is the only test here
 * that can fail for a reason the in-JVM suite structurally cannot see — the bootstrap's shell, the
 * image contract, the container's route back to the host, the binary's own linkage.
 *
 * <p>Run it with {@code -DskipITs=false}. It is tagged {@code extended} and the {@code native}
 * profile excludes that tag, as it does for every docker-backed IT here: a native build has to run
 * its ITs to be worth anything, and this one would fail it for reasons about a host's docker and
 * networking rather than about the binary.
 *
 * <p><b>The host-networking assumption every docker-backed IT here carries, and its caveat.</b>
 * The container reaches this JVM through {@code host.docker.internal} on {@code qits.ci.network} —
 * twice over, for the binary download and for the control socket — and the JUnit assumptions below
 * cover docker, the image and the daemon binary but <em>not</em> that route existing. On a host where
 * a container cannot get back to the JVM (plain WSL2 with no compose stack up) this fails rather than
 * skips. That is a property of the IT — do not "fix" it by weakening the assertions.
 *
 * <p><b>One half of that hazard is now handled and must stay handled:</b> a JVM left to itself binds
 * a dual-stack IPv6 socket for {@code 0.0.0.0}, which docker's host gateway does not forward, so
 * every listener this test stands up is invisible from the container. It does not fail as a
 * connection error either — it fails as the register deadline expiring with {@code wget could not
 * fetch} in the container's log, which reads like a broken bootstrap and costs an afternoon. {@code
 * service/pom.xml} gives failsafe {@code -Djava.net.preferIPv4Stack=true} for exactly this; it has
 * to be an {@code argLine} because the JVM reads it when networking initialises, before any test
 * runs. Delete it and this test regresses to a two-minute timeout with a misleading message.
 *
 * <p><b>The daemon binary is a system property, not a fixture.</b> {@code -Dqits.ci.daemon-binary=
 * <path>} points at whatever qits-ci-daemon's native build produced; the IT serves that file over
 * HTTP and hands its url to the container as {@code $QITS_CI_DAEMON_BINARY_URL}. That the url can
 * point anywhere is exactly why it is env — a file-served stand-in is indistinguishable from
 * qits-artifacts here, so this gate never waits on a publish. Without the property the two
 * binary-dependent cases skip; {@link #aContainerThatNeverRegistersIsReapedWithItsOwnLogCaptured}
 * does not need one and runs on docker alone.
 *
 * <p>The image is pinned to {@code buildpack-deps:scm}, verified to carry {@code git}, {@code bash},
 * {@code wget} and {@code curl} — the whole image contract, with both downloader arms present.
 */
@QuarkusTest
@Tag("extended")
public class CiDaemonHandshakeIT {

  /** Verified to satisfy the image contract: git, bash, and both wget and curl. */
  private static final String IMAGE = System.getProperty("qits.ci.step-image", "buildpack-deps:scm");

  private static final String RUNTIME = System.getProperty("qits.ci.container-runtime", "docker");

  /** Path to the binary qits-ci-daemon's native build produced. Absent ⇒ those cases skip. */
  private static final String BINARY = System.getProperty("qits.ci.daemon-binary");

  private static final String NETWORK = System.getProperty("qits.ci.network", "qits-net");

  private static final String REPO_ID = "ci-daemon-it-repo";

  private static final Duration REGISTER = Duration.ofSeconds(120);
  private static final Duration INITIALIZE = Duration.ofSeconds(120);
  private static final Duration FINISH = Duration.ofSeconds(120);

  @Inject CiDaemonRegistry registry;

  @TestHTTPResource("/ci/daemon")
  URI controlSocket;

  @Test
  public void aRealContainerRegistersInitializesRunsItsStepAndFinishes() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    assumeTrue(binaryAvailable(), "-Dqits.ci.daemon-binary=<path> required for this IT");

    List<String> chunks = Collections.synchronizedList(new ArrayList<>());
    withFixture(
        (launcher, sha, binaryUrl) -> {
          String runId = UUID.randomUUID().toString();
          CiDaemonRegistry.Credentials credentials =
              registry.registerLaunch(runId, 0, (stream, seq, text) -> chunks.add(text));
          CiDaemonLauncher.Launched launched =
              launcher.launch(
                  new LaunchSpec(
                      runId,
                      0,
                      REPO_ID,
                      "main",
                      sha,
                      IMAGE,
                      credentials.daemonId(),
                      credentials.secret(),
                      binaryUrl));
          try {
            assertTrue(launched.started(), "docker refused the launch: " + launched.error());

            assertTrue(
                registry.awaitRegistered(credentials.daemonId(), REGISTER),
                "the daemon never dialled back:\n" + launcher.logs(launched.containerName()));

            CiDaemonRegistry.Initialization initialization =
                registry.awaitInitialized(credentials.daemonId(), INITIALIZE);
            assertEquals(
                CiDaemonRegistry.Initialization.Status.INITIALIZED,
                initialization.status(),
                initialization + "\n" + launcher.logs(launched.containerName()));

            // The step is the answer to Initialized — the host initiates nothing toward a container.
            registry.sendRunStep(credentials.daemonId(), "echo marker-$(cat hello.txt) && pwd", 60);

            CiDaemonRegistry.Completion completion =
                registry.awaitFinished(credentials.daemonId(), FINISH);
            assertEquals(
                CiDaemonRegistry.Completion.Status.FINISHED,
                completion.status(),
                completion + "\n" + launcher.logs(launched.containerName()));
            assertEquals(0, completion.exitCode(), String.join("", chunks));
            assertFalse(completion.timedOut());

            String output = String.join("", chunks);
            // The daemon cloned at the pushed sha into its own /workspace and ran the script there.
            assertTrue(output.contains("marker-hello-from-ci-daemon-it"), output);
            assertTrue(output.contains("/workspace"), output);
          } finally {
            registry.reap(credentials.daemonId());
            launcher.reap(launched.containerName());
          }
        });
  }

  @Test
  public void aContainerLaunchedWithTheWrongSecretIsRefusedAndNeverRegisters() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");
    assumeTrue(binaryAvailable(), "-Dqits.ci.daemon-binary=<path> required for this IT");

    withFixture(
        (launcher, sha, binaryUrl) -> {
          String runId = UUID.randomUUID().toString();
          CiDaemonRegistry.Credentials credentials = registry.registerLaunch(runId, 0, null);
          CiDaemonLauncher.Launched launched =
              launcher.launch(
                  new LaunchSpec(
                      runId,
                      0,
                      REPO_ID,
                      "main",
                      sha,
                      IMAGE,
                      credentials.daemonId(),
                      // The one thing changed: this container holds a secret the host did not mint.
                      credentials.secret().substring(1) + "x",
                      binaryUrl));
          try {
            assertTrue(launched.started(), launched.error());
            assertFalse(
                registry.awaitRegistered(credentials.daemonId(), Duration.ofSeconds(60)),
                "a container presenting the wrong secret must never reach REGISTERED");
            // The daemon saw the 1008 and exited; its log is the diagnosis, as for every other
            // failure inside a container.
            assertFalse(launcher.logs(launched.containerName()).isBlank());
          } finally {
            registry.reap(credentials.daemonId());
            launcher.reap(launched.containerName());
          }
        });
  }

  @Test
  public void aContainerThatNeverRegistersIsReapedWithItsOwnLogCaptured() throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");

    withFixture(
        (launcher, sha, servedBinaryUrl) -> {
          String runId = UUID.randomUUID().toString();
          CiDaemonRegistry.Credentials credentials = registry.registerLaunch(runId, 0, null);
          // A binary url that 404s — the shape a blank qits.ci.daemon-version or a botched publish
          // produces. The container comes up, the bootstrap cannot fetch, nothing ever dials.
          CiDaemonLauncher.Launched launched =
              launcher.launch(
                  new LaunchSpec(
                      runId,
                      0,
                      REPO_ID,
                      "main",
                      sha,
                      IMAGE,
                      credentials.daemonId(),
                      credentials.secret(),
                      servedBinaryUrl + "-does-not-exist"));
          try {
            assertTrue(launched.started(), launched.error());
            assertFalse(
                registry.awaitRegistered(credentials.daemonId(), Duration.ofSeconds(60)),
                "nothing should have registered");

            // Captured BEFORE the reap, which is the whole reason --rm is gone: the bootstrap's own
            // stderr is the only account of why this container never became a daemon.
            String log = waitForLog(launcher, launched.containerName());
            assertTrue(log.contains("could not fetch"), "expected the bootstrap's report, got:\n" + log);
            assertTrue(log.contains(credentials.daemonId()) || log.contains("-does-not-exist"), log);
          } finally {
            registry.reap(credentials.daemonId());
            launcher.reap(launched.containerName());
          }
          assertFalse(
              containerExists(CiDaemonLauncher.containerName(runId, 0)),
              "the reap must actually remove the container");
        });
  }

  // --- fixture ----------------------------------------------------------------------------------

  private interface GateCase {
    void run(CiDaemonLauncher launcher, String sha, String binaryUrl) throws Exception;
  }

  /**
   * Stands the shared git-over-smart-HTTP + binary fixture up, then hands a hand-wired launcher, the
   * tip sha and the binary's url to the case.
   *
   * <p>The launcher is constructed rather than injected because its config is per-test — the served
   * port is not known until the server is listening, and this IT is about the transport rather than
   * about the production wiring ({@code CiDaemonGateIT} is the one that drives the injected beans).
   * The <b>registry</b> is the injected bean, since it must be the same one {@link CiDaemonSocket}
   * dispatches to.
   */
  private void withFixture(GateCase gateCase) throws Exception {
    Path work = Files.createTempDirectory("ci-daemon-it");
    byte[] binary = BINARY == null ? new byte[0] : Files.readAllBytes(Path.of(BINARY));
    try (GitHttpBackend fixture = GitHttpBackend.start(work, binary)) {
      Path bare = prepareServedBareRepo(work);
      String sha = exec(null, "git", "-C", bare.toString(), "rev-parse", "HEAD").trim();

      GitHttpBackend.awaitReachableFromAContainer(
          RUNTIME, IMAGE, NETWORK, fixture.port(), controlSocket.getPort());

      CiDaemonLauncher launcher = new CiDaemonLauncher();
      launcher.runtime = RUNTIME;
      launcher.network = NETWORK;
      launcher.containerGitUrl = fixture.containerGitUrl();
      // Told, never derived: the container is handed this exact string and parses nothing out of it.
      launcher.containerDaemonUrl =
          "ws://host.docker.internal:" + controlSocket.getPort() + controlSocket.getPath();
      launcher.daemonVersion = Optional.empty();
      launcher.daemonBinaryUrlTemplate = fixture.containerBinaryUrl();
      launcher.registerTimeoutSeconds = 180;
      launcher.outputMaxChars = 65536;
      launcher.memoryLimit = "2g";
      launcher.pidsLimit = "1024";
      launcher.cpus = "2";
      launcher.ensureNetwork();

      gateCase.run(launcher, sha, launcher.resolveBinaryUrl(""));
    } finally {
      deleteRecursively(work);
    }
  }

  /**
   * A bare repo with a single commit, laid out at {@code <work>/git/<repoId>} — the path {@code
   * git http-backend} resolves for {@code PATH_INFO=/git/<repoId>/…} under {@code
   * GIT_PROJECT_ROOT=<work>}, which is what lets the fixture's url shape be the production one
   * without the handler having to rewrite anything.
   *
   * <p>No {@code update-server-info}: that is the dumb transport's index, and this fixture
   * deliberately does not serve one.
   */
  private static Path prepareServedBareRepo(Path work) throws Exception {
    Path src = work.resolve("src");
    Files.createDirectories(src);
    exec(src, "git", "init", "-q", "-b", "main");
    exec(src, "git", "config", "user.email", "it@qits.local");
    exec(src, "git", "config", "user.name", "qits-it");
    Files.writeString(src.resolve("hello.txt"), "hello-from-ci-daemon-it");
    exec(src, "git", "add", "hello.txt");
    exec(src, "git", "commit", "-q", "-m", "initial");
    Path bare = work.resolve("git").resolve(REPO_ID);
    Files.createDirectories(bare.getParent());
    exec(work, "git", "clone", "-q", "--bare", src.toString(), bare.toString());
    return bare;
  }

  /** The container may still be writing when the register deadline expires; give the log a moment. */
  private static String waitForLog(CiDaemonLauncher launcher, String containerName)
      throws InterruptedException {
    for (int attempt = 0; attempt < 30; attempt++) {
      String log = launcher.logs(containerName);
      if (!log.isBlank()) {
        return log;
      }
      Thread.sleep(200);
    }
    return launcher.logs(containerName);
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

  private static boolean containerExists(String name) throws Exception {
    return new ProcessBuilder(RUNTIME, "container", "inspect", name).start().waitFor() == 0;
  }

  private static String exec(Path cwd, String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException(String.join(" ", argv) + " failed:\n" + out);
    }
    return out;
  }

  private static void deleteRecursively(Path root) throws Exception {
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
