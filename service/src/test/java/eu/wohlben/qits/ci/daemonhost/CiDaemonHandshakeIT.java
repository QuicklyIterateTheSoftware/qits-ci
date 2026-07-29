package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
 * profile excludes that tag, exactly as {@code CiDockerRunnerIT} is: a native build has to run its
 * ITs to be worth anything, and this one would fail it for reasons about a host's docker and
 * networking rather than about the binary.
 *
 * <p><b>The same host-networking assumption {@code CiDockerRunnerIT} carries, and the same caveat.</b>
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
   * Serves a one-commit bare over <b>smart</b> HTTP and, beside it, the daemon binary, then hands a
   * hand-wired launcher, the tip sha and the binary's url to the case.
   *
   * <p><b>Why smart HTTP and not static files.</b> The daemon clones with {@code --depth 50}, and a
   * shallow clone is a capability only the smart transport advertises — a static-file handler gets
   * exactly as far as {@code fatal: dumb http transport does not support shallow capabilities}. That
   * is the fixture being unrepresentative, not the daemon being wrong: depth 50 is deliberate (a
   * recent-but-not-tip sha must still be in the clone) and production qits-artifacts serves smart
   * HTTP at {@code /git/<repoId>}. So the fixture shells {@code git http-backend} as CGI, which is
   * what qits-artifacts is doing behind its own route.
   *
   * <p><b>What this reproduces and what it does not.</b> Reproduced: the wire protocol, the url
   * shape ({@code <base>/git/<repoId>}, with ci appending the {@code /git} segment itself), ref
   * advertisement, and shallow negotiation — everything the daemon's clone actually exercises. Not
   * reproduced: qits-artifacts' authorization, its repository lookup, and any of its own routing;
   * this handler exports one path unconditionally. That is the right split for this gate — it is
   * about the daemon's lifecycle through a real container, and assertions about what the git host
   * does belong in qits-artifacts.
   *
   * <p>The launcher is constructed rather than injected because its config is per-test — the served
   * port is not known until the server is listening. The <b>registry</b> is the injected bean, since
   * it must be the same one {@link CiDaemonSocket} dispatches to.
   */
  private void withFixture(GateCase gateCase) throws Exception {
    Path work = Files.createTempDirectory("ci-daemon-it");
    Vertx vertx = Vertx.vertx();
    HttpServer server = vertx.createHttpServer();
    try {
      Path bare = prepareServedBareRepo(work);
      String sha = exec(null, "git", "-C", bare.toString(), "rev-parse", "HEAD").trim();
      byte[] binary = BINARY == null ? new byte[0] : Files.readAllBytes(Path.of(BINARY));

      server.requestHandler(
          req -> {
            if (req.path().equals("/qits-ci-daemon")) {
              req.response()
                  .putHeader("Content-Type", "application/octet-stream")
                  .end(Buffer.buffer(binary));
              return;
            }
            serveSmartHttp(vertx, req, work);
          });
      int port =
          server
              .listen(0, "0.0.0.0")
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS)
              .actualPort();

      awaitReachableFromAContainer(port, controlSocket.getPort());

      CiDaemonLauncher launcher = new CiDaemonLauncher();
      launcher.runtime = RUNTIME;
      launcher.network = "qits-net";
      launcher.containerGitUrl = "http://host.docker.internal:" + port;
      // Told, never derived: the container is handed this exact string and parses nothing out of it.
      launcher.containerDaemonUrl =
          "ws://host.docker.internal:" + controlSocket.getPort() + controlSocket.getPath();
      launcher.daemonVersion = Optional.empty();
      launcher.daemonBinaryUrlTemplate = "http://host.docker.internal:" + port + "/qits-ci-daemon";
      launcher.registerTimeoutSeconds = 180;
      launcher.outputMaxChars = 65536;
      launcher.memoryLimit = "2g";
      launcher.pidsLimit = "1024";
      launcher.cpus = "2";
      launcher.ensureNetwork();

      gateCase.run(launcher, sha, launcher.resolveBinaryUrl(""));
    } finally {
      server.close();
      vertx.close();
      deleteRecursively(work);
    }
  }

  /**
   * Block until a container can actually open a TCP connection to each of this JVM's freshly-bound
   * ports, and fail honestly if it never can.
   *
   * <p><b>Why this exists.</b> Docker's host-gateway forwarding does not pick up a listener the
   * instant it binds — measured on this host, a container launched immediately after {@code
   * listen()} returns gets {@code Connection refused}, and the very next attempt two seconds later
   * downloads all 45MB. The step container's bootstrap fetches <em>once</em> and exits when it
   * cannot, so losing that race does not look like a race: the container is dead within a second and
   * the host then waits out its full register deadline, reporting {@code wget could not fetch} two
   * minutes later. That reads like a broken bootstrap or a bad url, and it is neither.
   *
   * <p>Waiting here rather than retrying in the bootstrap is deliberate. The race is a property of
   * <em>this fixture</em> — production ports belong to long-lived services that were listening long
   * before any container started — so the fix belongs in the fixture. Adding a retry loop to the
   * host-authored bootstrap to paper over a test-harness timing artefact would be changing shipped
   * behaviour to suit a test.
   *
   * <p>It doubles as the honest form of the host-networking caveat in this class's javadoc: on a
   * host where a container genuinely cannot route back to the JVM, this fails in a minute saying so,
   * instead of every case failing later for an apparently unrelated reason.
   */
  private static void awaitReachableFromAContainer(int... ports) throws Exception {
    StringBuilder script = new StringBuilder();
    for (int port : ports) {
      // A bare TCP connect, so one probe covers both the fixture's HTTP server and the control
      // socket without caring what either would answer.
      script
          .append("timeout 5 bash -c 'exec 3<>/dev/tcp/host.docker.internal/")
          .append(port)
          .append("' || exit 1\n");
    }
    script.append("echo REACHABLE\n");

    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    String last = "";
    while (System.nanoTime() < deadline) {
      Process probe =
          new ProcessBuilder(
                  RUNTIME,
                  "run",
                  "--rm",
                  "--network",
                  "qits-net",
                  "--add-host=host.docker.internal:host-gateway",
                  "--entrypoint",
                  "/bin/bash",
                  IMAGE,
                  "-c",
                  script.toString())
              .redirectErrorStream(true)
              .start();
      last = new String(probe.getInputStream().readAllBytes()).strip();
      probe.waitFor();
      if (last.contains("REACHABLE")) {
        return;
      }
      Thread.sleep(1000);
    }
    throw new AssertionError(
        "A container on qits-net could not reach this JVM's ports "
            + Arrays.toString(ports)
            + " through host.docker.internal within 90s. This IT needs that route (see the class"
            + " javadoc); on a host without one it fails rather than skips, exactly as"
            + " CiDockerRunnerIT does. Last probe output:\n"
            + last);
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

  /**
   * Bridge one HTTP request into {@code git http-backend}, the CGI program git ships for exactly
   * this. Runs on a worker thread — it spawns a process and reads it to completion, which an event
   * loop must not do.
   */
  private static void serveSmartHttp(Vertx vertx, HttpServerRequest req, Path projectRoot) {
    String method = req.method().name();
    String pathInfo = req.path();
    String query = req.query() == null ? "" : req.query();
    String contentType = req.getHeader("Content-Type");
    String contentEncoding = req.getHeader("Content-Encoding");
    req.body()
        .onComplete(
            read -> {
              byte[] body =
                  read.succeeded() && read.result() != null ? read.result().getBytes() : new byte[0];
              vertx
                  .executeBlocking(
                      () ->
                          gitHttpBackend(
                              projectRoot, method, pathInfo, query, contentType, contentEncoding,
                              body),
                      false)
                  .onComplete(
                      cgi -> {
                        if (cgi.failed()) {
                          req.response().setStatusCode(500).end(String.valueOf(cgi.cause()));
                          return;
                        }
                        HttpServerResponse response = req.response();
                        response.setStatusCode(cgi.result().status());
                        cgi.result().headers().forEach(response::putHeader);
                        response.end(Buffer.buffer(cgi.result().body()));
                      });
            });
  }

  /** A CGI program's answer: the {@code Status:} line, the headers it set, and the body. */
  private record CgiResponse(int status, Map<String, String> headers, byte[] body) {}

  /**
   * Run {@code git http-backend} with the CGI environment it expects and split its response.
   *
   * <p>stdin is written on its own thread while stdout is read on this one: the request body is
   * small, but a CGI program that starts answering before it has consumed its input deadlocks a
   * write-then-read implementation, and that is a bad way to spend an afternoon.
   */
  private static CgiResponse gitHttpBackend(
      Path projectRoot,
      String method,
      String pathInfo,
      String query,
      String contentType,
      String contentEncoding,
      byte[] body)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder("git", "http-backend");
    Map<String, String> env = pb.environment();
    env.put("GIT_PROJECT_ROOT", projectRoot.toString());
    // The fixture exports what it serves; qits-artifacts' own authorization is not modelled here.
    env.put("GIT_HTTP_EXPORT_ALL", "1");
    env.put("REQUEST_METHOD", method);
    env.put("PATH_INFO", pathInfo);
    env.put("QUERY_STRING", query);
    env.put("REMOTE_ADDR", "127.0.0.1");
    env.put("CONTENT_LENGTH", String.valueOf(body.length));
    if (contentType != null) {
      env.put("CONTENT_TYPE", contentType);
    }
    if (contentEncoding != null) {
      // git clients may gzip an upload-pack request; http-backend inflates it when told.
      env.put("HTTP_CONTENT_ENCODING", contentEncoding);
    }

    Process process = pb.start();
    Thread.startVirtualThread(
        () -> {
          try (var stdin = process.getOutputStream()) {
            stdin.write(body);
          } catch (Exception ignored) {
            // the child closed its input; whatever it already read is what it answers on
          }
        });
    Thread stderr =
        Thread.startVirtualThread(
            () -> {
              try (var err = process.getErrorStream()) {
                err.readAllBytes(); // drained so a chatty failure cannot fill the pipe and wedge us
              } catch (Exception ignored) {
                // nothing to report beyond the exit code
              }
            });
    byte[] raw = process.getInputStream().readAllBytes();
    process.waitFor();
    stderr.join(TimeUnit.SECONDS.toMillis(5));
    return parseCgi(raw);
  }

  /** Split a CGI response into headers and body at the first blank line, honouring {@code Status}. */
  private static CgiResponse parseCgi(byte[] raw) {
    int split = -1;
    int bodyAt = -1;
    for (int i = 0; i + 1 < raw.length; i++) {
      if (raw[i] == '\n' && raw[i + 1] == '\n') {
        split = i;
        bodyAt = i + 2;
        break;
      }
      if (i + 3 < raw.length
          && raw[i] == '\r'
          && raw[i + 1] == '\n'
          && raw[i + 2] == '\r'
          && raw[i + 3] == '\n') {
        split = i;
        bodyAt = i + 4;
        break;
      }
    }
    if (split < 0) {
      return new CgiResponse(500, Map.of(), raw);
    }
    int status = 200;
    Map<String, String> headers = new LinkedHashMap<>();
    String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
    for (String line : head.split("\\R")) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String name = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (name.equalsIgnoreCase("Status")) {
        status = Integer.parseInt(value.split("\\s+")[0]);
      } else if (!name.equalsIgnoreCase("Content-Length")
          && !name.equalsIgnoreCase("Transfer-Encoding")) {
        // Both are ours to decide: the body is written whole, so vert.x sets the framing.
        headers.put(name, value);
      }
    }
    return new CgiResponse(status, headers, Arrays.copyOfRange(raw, bodyAt, raw.length));
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
