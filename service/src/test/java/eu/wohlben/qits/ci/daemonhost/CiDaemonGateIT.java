package eu.wohlben.qits.ci.daemonhost;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.bus.ScmPublishCommitListener;
import eu.wohlben.qits.ci.bus.ScmPushFrames;
import eu.wohlben.qits.ci.githost.StubGitHost;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>The gate.</b> The whole lifecycle, end to end, with nothing faked: a real push into a real bare,
 * the real intake, real containers from a real image each downloading a real daemon binary, real
 * chunks over a real socket, and the real read surface asserted from outside the process.
 *
 * <p>Everything else in this repository is docker-free, which means everything else is blind to the
 * things that only exist once a container is running: the bootstrap's shell, the image contract, the
 * route back to this JVM, the binary's linkage, whether a daemon really enforces a deadline, and
 * whether a step spraying megabytes really costs bounded memory. That list is exactly this class's
 * subject, and it is the only place a step's script is really executed at all — no fake anywhere in
 * this repo runs one.
 *
 * <p>Five cases, one per thing the phase is finished when it does:
 *
 * <ol>
 *   <li>a two-step pipeline through the intake, followed live mid-run and read back as terminal
 *       per-step rows with host-stamped timestamps afterwards — with a deliberately <b>noisy</b>
 *       step, because bounded memory under a chunk flood is a property with no other home;
 *   <li>a cancellation honored mid-step;
 *   <li>a per-step {@code timeout-seconds} recorded as timed out rather than as a failure;
 *   <li>an image that cannot satisfy the contract, so the never-registered state is the container's
 *       own log rather than a generic step failure;
 *   <li>a step declaring {@code docker: true} really reaching the host's daemon through the mounted
 *       socket, beside a step that declared nothing and really has none.
 * </ol>
 *
 * <p>Run it with {@code -DskipITs=false}. It is tagged {@code extended} and the {@code native}
 * profile excludes that tag: a native build has to run its ITs to be worth anything, and this one
 * would fail it for reasons about a host's docker and networking rather than about the binary.
 *
 * <p><b>The host-networking assumption, and its caveat.</b> Containers reach this JVM through {@code
 * host.docker.internal} — for the binary download, for the clone, and for the control socket — and
 * the assumptions below cover docker, the image and the daemon binary but <em>not</em> that route
 * existing. On a host where a container cannot get back to the JVM (plain WSL2 with no compose stack
 * up) this fails rather than skips. That is a property of the IT; do not "fix" it by weakening the
 * assertions. {@code service/pom.xml} gives failsafe {@code -Djava.net.preferIPv4Stack=true} for the
 * other half of the same hazard and it has to stay.
 *
 * <p><b>The daemon binary is a system property, not a fixture.</b> {@code
 * -Dqits.ci.daemon-binary=<path>} points at whatever qits-ci-daemon's native build produced; that
 * the url can point anywhere is exactly why it is env, so this gate never waits on a publish to
 * qits-artifacts. Without the property every case here skips.
 *
 * <p><b>It drives the injected beans, not hand-wired ones</b> — the production path is the subject.
 * Two consequences, both commented where they happen: the container-facing config is overridden
 * through a {@link QuarkusTestProfile} whose {@code getConfigOverrides()} starts the fixture first
 * (the served port has to exist before the app boots), and the one value that cannot be known even
 * then — the control-socket url, which carries this JVM's own test port — is written onto the
 * launcher through its CDI proxy in {@link #wireTheLauncherToThisJvm()}.
 */
@QuarkusTest
@Tag("extended")
@TestProfile(CiDaemonGateIT.GateProfile.class)
public class CiDaemonGateIT {

  /** Verified to satisfy the image contract: git, bash, and both wget and curl. */
  private static final String IMAGE = System.getProperty("qits.ci.step-image", "buildpack-deps:scm");

  /**
   * Has a shell and nothing else the contract asks for: no {@code wget}, no {@code curl}, no {@code
   * git}. So the bootstrap runs, finds no downloader, says so on stderr, and exits — which is the
   * never-registered state with its own diagnosis, and it is reached through the shipped bootstrap
   * rather than by breaking a url.
   */
  private static final String IMAGE_WITHOUT_THE_CONTRACT =
      System.getProperty("qits.ci.contractless-image", "debian:bookworm-slim");

  private static final String RUNTIME = System.getProperty("qits.ci.container-runtime", "docker");

  private static final String NETWORK = System.getProperty("qits.ci.network", "qits-net");

  /** Path to the binary qits-ci-daemon's native build produced. Absent ⇒ everything here skips. */
  private static final String BINARY = System.getProperty("qits.ci.daemon-binary");

  /** The all-zero sha git reports as the old id of a newly created branch. */
  private static final String ZERO_SHA = "0".repeat(40);

  /** The daemon build this run pins itself to; asserted back off the run row. */
  private static final String DAEMON_VERSION = "gate-build";

  /** The shipped bound, and what the flood case asserts the live tail against. */
  private static final int OUTPUT_MAX_CHARS = 65536;

  @Inject CiDaemonLauncher launcher;

  @Inject ScmPublishCommitListener pushes;

  @Inject CiStepRelay relay;

  @TestHTTPResource("/ci/daemon")
  URI controlSocket;

  /**
   * Everything a container needs to reach, resolved before the app boots.
   *
   * <p>{@code getConfigOverrides()} is the only hook that runs early enough to hand the application
   * a port that does not exist yet, so the fixture is started here rather than in a {@code @BeforeAll}
   * — by then {@code CiDaemonLauncher} has already been configured. One directory is served twice,
   * which is exactly the split the two production keys have: {@link StubGitHost} answers the content
   * reads ci makes for itself, {@code GitHttpBackend} answers the containers' clones over the smart
   * protocol.
   */
  public static class GateProfile implements QuarkusTestProfile {

    static final Path GIT_ROOT = Path.of("target", "ci-gate-it-git-host").toAbsolutePath();
    static GitHttpBackend fixture;
    static StubGitHost.Server contentHost;

    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        deleteRecursively(GIT_ROOT);
        Files.createDirectories(GIT_ROOT.resolve("git"));
        byte[] binary = BINARY == null ? new byte[0] : Files.readAllBytes(Path.of(BINARY));
        fixture = GitHttpBackend.start(GIT_ROOT, binary);
        contentHost = StubGitHost.start(GIT_ROOT);
      } catch (Exception e) {
        throw new IllegalStateException("could not start the gate fixture", e);
      }
      Map<String, String> overrides = new HashMap<>();
      // Off, so the real CiDaemonStepRunner is the bean under test. A @Mock alternative replaces its
      // bean across the whole test application, so without this the gate would assert against
      // FakeCiStepRunner — and it would do it in under a second, which is exactly how long it takes
      // to believe a green gate that proved nothing.
      overrides.put(eu.wohlben.qits.ci.control.FakeCiStepRunner.ENABLED, "false");
      overrides.put("qits.ci.git-host-url", contentHost.gitHostUrl());
      overrides.put("qits.ci.container-git-url", fixture.containerGitUrl());
      // No {version} placeholder: the fixture serves one binary, and the version is what lands on
      // the run row. The two still travel together, which is the point of the template.
      overrides.put("qits.ci.daemon-binary-url-template", fixture.containerBinaryUrl());
      overrides.put("qits.ci.daemon-version", DAEMON_VERSION);
      overrides.put("qits.ci.network", NETWORK);
      overrides.put("qits.ci.container-runtime", RUNTIME);
      // Generous: a cold image pull plus a 45MB download over the host gateway.
      overrides.put("qits.ci.daemon-register-timeout-seconds", "180");
      overrides.put("qits.ci.daemon-init-timeout-seconds", "180");
      overrides.put("qits.ci.step-timeout-grace-seconds", "30");
      overrides.put("qits.ci.output-max-chars", String.valueOf(OUTPUT_MAX_CHARS));
      return overrides;
    }
  }

  /**
   * The one thing the profile could not know: this JVM's test port, and therefore the url a container
   * dials back on.
   *
   * <p>Written through {@link ClientProxy#unwrap} because a field set on an injected bean lands on
   * the client proxy rather than on the contextual instance. It is a deliberate, local ugliness and
   * not a pattern to spread — the alternative was to pin the test port in advance and race whatever
   * else on the machine wanted it, which trades a visible hack for an intermittent one.
   *
   * <p>Also does what the boot observer would have: {@code ensureNetwork} is skipped under {@code
   * TEST} launch mode, by design, so the suites cannot mutate a developer's docker daemon by
   * accident. This IT is the exception that asks for it explicitly.
   */
  @BeforeEach
  void wireTheLauncherToThisJvm() throws Exception {
    assumeTrue(dockerAndImageAvailable(IMAGE), "docker + " + IMAGE + " required for this IT");
    assumeTrue(binaryAvailable(), "-Dqits.ci.daemon-binary=<path> required for this IT");

    CiDaemonLauncher real = ClientProxy.unwrap(launcher);
    real.containerDaemonUrl =
        "ws://host.docker.internal:" + controlSocket.getPort() + controlSocket.getPath();
    real.ensureNetwork();

    GitHttpBackend.awaitReachableFromAContainer(
        RUNTIME, IMAGE, NETWORK, GateProfile.fixture.port(), controlSocket.getPort());
  }

  @Test
  public void aTwoStepPipelineStreamsLiveAndLandsAsTerminalRowsWithTimestamps() throws Exception {
    // Step 0 is deliberately noisy AND slow: the flood is the chunk-flood risk (the relay and the
    // persisted tail must both stay bounded while a step sprays megabytes), and the sleep after it
    // is the window in which "follow along" is a real thing to do.
    String repoId = seedOrigin();
    String sha =
        pushBranchWithConfig(
            repoId,
            "gate-green",
            """
            steps:
              - image: %s
                script: |
                  echo step-one-says-$(cat hello.txt)
                  yes qits-ci-flood | head -n 200000
                  sleep 6
                  echo step-one-done
              - image: %s
                script: |
                  echo step-two-in-$(pwd)
                  echo step-two-done
            """
                .formatted(IMAGE, IMAGE));
    announcePush(repoId, "gate-green", sha);

    String runId = awaitRunId(repoId);

    // --- live, mid-run -------------------------------------------------------------------------
    JsonPath live = awaitLiveStep(runId, 0, "qits-ci-flood");
    Map<String, Object> liveStep = live.getMap("live");
    assertEquals(0, liveStep.get("stepIndex"), "the run is on its first step");
    String liveOutput = liveStep.get("output").toString();
    assertTrue(liveOutput.contains("qits-ci-flood"), "the flood must be visible while it runs");
    // A step printing megabytes costs the same memory as one printing a line.
    assertTrue(
        liveOutput.length() <= OUTPUT_MAX_CHARS,
        "the live relay must stay bounded under a flood, got " + liveOutput.length() + " chars");
    assertEquals("RUNNING", live.getString("status"));
    // Persist-at-finish: while step 0 runs it has no row at all, which is what `live` exists to make
    // legible instead of looking like a run with missing steps.
    assertTrue(live.getList("steps").size() < 2, "a running step must not have a row yet");

    // --- terminal ------------------------------------------------------------------------------
    JsonPath detail = awaitTerminalRun(runId);
    assertEquals("SUCCESS", detail.getString("status"), detail.prettify());
    assertEquals(DAEMON_VERSION, detail.getString("daemonVersion"), "the run pins its daemon build");
    assertNull(detail.get("live"), "a finished run exposes nothing live");

    List<Map<String, Object>> steps = detail.getList("steps");
    assertEquals(2, steps.size());
    for (Map<String, Object> step : steps) {
      assertEquals("SUCCESS", step.get("status"), step.toString());
      assertEquals(0, step.get("exitCode"));
      Instant startedAt = Instant.parse(step.get("startedAt").toString());
      Instant finishedAt = Instant.parse(step.get("finishedAt").toString());
      assertFalse(finishedAt.isBefore(startedAt), "finished_at must not precede started_at");
    }
    // Step 0's own container cloned at the pushed sha and read the committed file.
    String first = steps.get(0).get("output").toString();
    assertTrue(first.contains("step-one-done"), first);
    assertTrue(first.length() <= OUTPUT_MAX_CHARS, "the persisted tail is bounded too");
    assertTrue(first.contains("output truncated"), "a flooded tail must say its head was dropped");
    // Step 1 got its OWN fresh container and its own fresh clone — no state crosses steps.
    String second = steps.get(1).get("output").toString();
    assertTrue(second.contains("step-two-in-/workspace"), second);
    assertTrue(second.contains("step-two-done"), second);
    assertFalse(second.contains("qits-ci-flood"), "no output may cross between step containers");

    // Nothing is left holding memory or a container once a run closes.
    assertEquals(0, relay.size(), "the relay must be dropped when the run closes");
    assertEquals(0, containersLabelled(runId), "every step container must have been reaped");
  }

  @Test
  public void aCancellationMidStepFailsThatStepAndSkipsTheRest() throws Exception {
    String repoId = seedOrigin();
    String sha =
        pushBranchWithConfig(
            repoId,
            "gate-cancel",
            """
            steps:
              - image: %s
                script: |
                  echo step-one-is-slow
                  sleep 300
              - image: %s
                script: echo never-runs
            """
                .formatted(IMAGE, IMAGE));
    announcePush(repoId, "gate-cancel", sha);

    String runId = awaitRunId(repoId);
    awaitLiveStep(runId, 0, "step-one-is-slow");

    given().when().post("/ci/api/runs/" + runId + "/cancel").then().statusCode(202);

    JsonPath detail = awaitTerminalRun(runId);
    assertEquals("FAILED", detail.getString("status"), detail.prettify());
    List<Map<String, Object>> steps = detail.getList("steps");
    assertEquals(2, steps.size());
    assertEquals("FAILED", steps.get(0).get("status"));
    // A cancelled step still FINISHES — the daemon answers the Cancel with a terminal frame — so
    // being cancelled is recorded from the host's flag and could not have been inferred otherwise.
    assertTrue(steps.get(0).get("output").toString().contains("cancelled"), steps.get(0).toString());
    assertTrue(steps.get(0).get("output").toString().contains("step-one-is-slow"));
    assertEquals("SKIPPED", steps.get(1).get("status"));
    assertNull(steps.get(1).get("startedAt"), "a skipped step never started");

    assertEquals(0, containersLabelled(runId), "cancelling must reap the container it stopped");
    // And the run is over, so a second cancellation has nothing to stop.
    given().when().post("/ci/api/runs/" + runId + "/cancel").then().statusCode(409);
  }

  @Test
  public void aStepExceedingItsDeclaredTimeoutIsRecordedTimedOutRatherThanFailed() throws Exception {
    String repoId = seedOrigin();
    String sha =
        pushBranchWithConfig(
            repoId,
            "gate-timeout",
            """
            steps:
              - image: %s
                timeout-seconds: 5
                script: |
                  echo before-the-hang
                  sleep 300
              - image: %s
                script: echo never-runs
            """
                .formatted(IMAGE, IMAGE));
    announcePush(repoId, "gate-timeout", sha);

    String runId = awaitRunId(repoId);
    JsonPath detail = awaitTerminalRun(runId);

    assertEquals("FAILED", detail.getString("status"), detail.prettify());
    List<Map<String, Object>> steps = detail.getList("steps");
    String output = steps.get(0).get("output").toString();
    // The whole point of the timedOut flag: a killed child reports the KILL's exit code, which a
    // script trapping a signal could produce on its own. It is recorded as a timeout regardless.
    assertTrue(output.contains("[step timed out]"), output);
    assertFalse(output.contains("cancelled"), "a timeout is not a cancellation");
    assertTrue(output.contains("before-the-hang"), output);
    assertEquals("FAILED", steps.get(0).get("status"));
    assertEquals("SKIPPED", steps.get(1).get("status"));

    // It really was the DECLARED five seconds and not the deployment default, which is 900.
    Instant startedAt = Instant.parse(steps.get(0).get("startedAt").toString());
    Instant finishedAt = Instant.parse(steps.get(0).get("finishedAt").toString());
    assertTrue(
        finishedAt.isBefore(startedAt.plusSeconds(120)),
        "the per-step timeout must be what ended it, not the global one");
    assertEquals(0, containersLabelled(runId));
  }

  /**
   * The socket mount, proven from both sides in one run: step 0 declares nothing and must find no
   * socket, step 1 declares {@code docker: true} and must reach the host's daemon through it. Argv
   * assembly is {@code CiDaemonLauncherTest}'s subject; what only a real container can show is that
   * the mount is a live socket the step really talks to, and that the step next to it really has none.
   *
   * <p>It asks the daemon's own {@code /version} with {@code curl --unix-socket} rather than with
   * {@code docker version}, because the docker CLI is not part of the image contract (git, bash, a
   * downloader) and a publishing repository supplies it in its own step image. The endpoint is the one
   * {@code docker version} itself calls, and the property under test is the mount rather than the
   * presence of a CLI.
   */
  @Test
  public void aStepDeclaringDockerReachesTheHostDaemonAndOneWithoutHasNoSocket() throws Exception {
    String repoId = seedOrigin();
    String sha =
        pushBranchWithConfig(
            repoId,
            "gate-docker",
            """
            steps:
              - image: %s
                script: |
                  test ! -e /var/run/docker.sock
                  echo no-socket-without-the-flag
              - image: %s
                docker: true
                script: |
                  curl -fsS --unix-socket /var/run/docker.sock http://localhost/version
                  echo the-socket-answered
            """
                .formatted(IMAGE, IMAGE));
    announcePush(repoId, "gate-docker", sha);

    String runId = awaitRunId(repoId);
    JsonPath detail = awaitTerminalRun(runId);
    assertEquals("SUCCESS", detail.getString("status"), detail.prettify());

    List<Map<String, Object>> steps = detail.getList("steps");
    assertEquals(2, steps.size());
    // The absence, against a real container: a step that declared nothing has no socket to find.
    assertTrue(
        steps.get(0).get("output").toString().contains("no-socket-without-the-flag"),
        steps.get(0).toString());
    // And the presence: the mounted socket is the host's docker daemon, answering its own API.
    String published = steps.get(1).get("output").toString();
    assertTrue(published.contains("the-socket-answered"), published);
    assertTrue(published.contains("ApiVersion"), "the daemon's own /version must be what answered: " + published);
    assertEquals(0, containersLabelled(runId));
  }

  @Test
  public void anImageThatCannotFetchTheDaemonRecordsItsOwnLogRatherThanAStepFailure() throws Exception {
    assumeTrue(
        dockerAndImageAvailable(IMAGE_WITHOUT_THE_CONTRACT),
        "docker + " + IMAGE_WITHOUT_THE_CONTRACT + " required for this case");

    String repoId = seedOrigin();
    String sha =
        pushBranchWithConfig(
            repoId,
            "gate-never-registers",
            """
            steps:
              - image: %s
                script: echo should-never-run
            """
                .formatted(IMAGE_WITHOUT_THE_CONTRACT));
    announcePush(repoId, "gate-never-registers", sha);

    String runId = awaitRunId(repoId);
    JsonPath detail = awaitTerminalRun(runId);

    assertEquals("FAILED", detail.getString("status"), detail.prettify());
    Map<String, Object> step = detail.<Map<String, Object>>getList("steps").get(0);
    String output = step.get("output").toString();
    // Distinguishable by construction: this is not "the step failed", it is "nothing ever became a
    // daemon", and the only account of why is the container's own output — captured BEFORE the reap,
    // which is the whole reason these containers do not carry --rm.
    assertTrue(
        output.contains("never started its ci daemon"),
        "the never-registered state must record as itself, got:\n" + output);
    // ...and the container's own account of why is what the row carries.
    assertTrue(
        output.contains("neither wget nor curl"),
        "the bootstrap's own stderr is the diagnosis, got:\n" + output);
    assertFalse(output.contains("should-never-run"), "the script must never have run");
    assertEquals(0, containersLabelled(runId));
  }

  // --- the push, as qits-githost announces it ---------------------------------------------------

  private void announcePush(String repoId, String branch, String newSha) {
    pushes.onFrame(ScmPushFrames.push(repoId, branch, ZERO_SHA, newSha));
  }

  // --- polling the read surface, which is the only way this test looks at anything ---------------

  private String awaitRunId(String repoId) throws Exception {
    long deadline = System.currentTimeMillis() + 60_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs =
          given()
              .when()
              .get("/ci/api/runs?repositoryId=" + repoId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("runs");
      if (!runs.isEmpty()) {
        return runs.get(0).get("id").toString();
      }
      Thread.sleep(200);
    }
    return fail("no CI run appeared for " + repoId + " within the deadline");
  }

  /** Poll the run until its {@code live} object shows the given step having printed {@code marker}. */
  private JsonPath awaitLiveStep(String runId, int stepIndex, String marker) throws Exception {
    long deadline = System.currentTimeMillis() + 300_000;
    String lastSeen = "(never populated)";
    while (System.currentTimeMillis() < deadline) {
      JsonPath run = getRun(runId);
      Map<String, Object> live = run.getMap("live");
      if (live != null) {
        lastSeen = String.valueOf(live.get("output"));
        if (Integer.valueOf(stepIndex).equals(live.get("stepIndex")) && lastSeen.contains(marker)) {
          return run;
        }
      }
      if (!"RUNNING".equals(run.getString("status"))) {
        return fail(
            "the run finished before its live output showed '" + marker + "':\n" + run.prettify());
      }
      Thread.sleep(200);
    }
    return fail("no live output containing '" + marker + "'; last saw:\n" + lastSeen);
  }

  private JsonPath awaitTerminalRun(String runId) throws Exception {
    long deadline = System.currentTimeMillis() + 300_000;
    while (System.currentTimeMillis() < deadline) {
      JsonPath run = getRun(runId);
      if (!"RUNNING".equals(run.getString("status"))) {
        return run;
      }
      Thread.sleep(250);
    }
    return fail("CI run " + runId + " never reached a terminal status");
  }

  private JsonPath getRun(String runId) {
    return given().when().get("/ci/api/runs/" + runId).then().statusCode(200).extract().jsonPath();
  }

  // --- git plumbing: the served bare doubles as ci's own file:// git host ------------------------

  private Path gitHostRoot() {
    return GateProfile.GIT_ROOT.resolve("git");
  }

  private String seedOrigin() throws Exception {
    String repoId = "gate-" + UUID.randomUUID();
    Path seed = Files.createTempDirectory("ci-gate-seed");
    git(seed, "init", "-q", "-b", "main");
    Files.writeString(seed.resolve("hello.txt"), "hello-from-the-gate");
    commitAll(seed, "initial");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  private String pushBranchWithConfig(String repoId, String branch, String config) throws Exception {
    Path clone = Files.createTempDirectory("ci-gate-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitHostRoot().resolve(repoId).toString(), clone.toString());
    git(clone, "checkout", "-q", "-b", branch);
    Path configFile = clone.resolve(".config/qits/ci-post-receive.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, config);
    commitAll(clone, "add ci config");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", branch);
    return sha;
  }

  private void commitAll(Path clone, String message) throws Exception {
    git(clone, "add", ".");
    git(clone, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", message);
  }

  private String git(Path cwd, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed:\n" + out);
    }
    return out;
  }

  // --- docker, only to check what the production path claims it did ------------------------------

  /** How many containers still carry this run's label. Zero after every path, including the bad ones. */
  private int containersLabelled(String runId) throws Exception {
    Process ps =
        new ProcessBuilder(
                RUNTIME, "ps", "-aq", "--filter", "label=" + CiDaemonLauncher.RUN_LABEL + "=" + runId)
            .redirectErrorStream(true)
            .start();
    String out = new String(ps.getInputStream().readAllBytes()).strip();
    ps.waitFor();
    return out.isEmpty() ? 0 : out.split("\\R").length;
  }

  private static boolean dockerAndImageAvailable(String image) {
    try {
      return new ProcessBuilder(RUNTIME, "image", "inspect", image).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean binaryAvailable() {
    return BINARY != null && Files.isRegularFile(Path.of(BINARY));
  }

  private static void deleteRecursively(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
