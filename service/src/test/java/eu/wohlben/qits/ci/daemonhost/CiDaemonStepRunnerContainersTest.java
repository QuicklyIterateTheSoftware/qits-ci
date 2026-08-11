package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.TokenSource;
import eu.wohlben.qits.ci.control.CiStepRunner.StepListener;
import eu.wohlben.qits.ci.control.CiStepRunner.StepOutcome;
import eu.wohlben.qits.ci.control.CiStepRunner.StepResult;
import eu.wohlben.qits.ci.control.CiStepRunner.StepSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the step runner does with the orchestrator's answers, end to end through the real runner
 * against a scripted {@link StubContainersServer}.
 *
 * <p><b>Three claims, and each of them used to be two docker calls in a particular order.</b> A
 * refused create is recorded as the step never having run; a container that never initialized has
 * its log captured by the very call that removes it; and the unconditional reap in the {@code
 * finally} tolerates finding nothing, which is what lets it stay unconditional after a branch has
 * already removed the container.
 *
 * <p><b>The registry is a subclass rather than a mock, and the launcher is the real one.</b> What is
 * under test is the runner's wiring between the two, so the half that would need a websocket and a
 * real daemon is overridden and the half that speaks HTTP is left alone. No docker, no application,
 * no database.
 */
public class CiDaemonStepRunnerContainersTest {

  private static final String RUN_ID = "0123456789abcdef-run";

  /** What {@code containerName} makes of this run's step 2 — the container name and the ref. */
  private static final String CONTAINER = "qits-ci-01234567-412621e6-2";

  private static final String DELETE_PATH =
      "/containers/api/containers/dev-qits-ci/ci-step/" + CONTAINER;

  /** A registry that answers whatever a case needs, with no socket and no daemon anywhere. */
  private static final class ScriptedRegistry extends CiDaemonRegistry {

    private boolean registered = true;

    private CiDaemonRegistry.Initialization initialization =
        CiDaemonRegistry.Initialization.of(CiDaemonRegistry.Initialization.Status.INITIALIZED);

    @Override
    public Credentials registerLaunch(String runId, int stepIndex, StepListener listener) {
      return new Credentials("daemon-7", "s3cr3t");
    }

    @Override
    public boolean awaitRegistered(String daemonId, Duration timeout) {
      return registered;
    }

    @Override
    public CiDaemonRegistry.Initialization awaitInitialized(String daemonId, Duration timeout) {
      return initialization;
    }

    @Override
    public void reap(String daemonId) {}
  }

  private CiDaemonStepRunner runner(String url, ScriptedRegistry registry) {
    CiDaemonLauncher launcher = new CiDaemonLauncher();
    launcher.owner = "dev-qits-ci";
    launcher.network = "qits-net";
    launcher.containerGitUrl = "http://qits-githost:8080";
    launcher.containerDaemonUrl = "ws://qits-ci:8080/ci/daemon";
    launcher.daemonBinaryUrlTemplate = "http://qits-artifacts:8080/artifacts/daemons/{version}";
    launcher.registerTimeoutSeconds = 60;
    launcher.initTimeoutSeconds = 120;
    launcher.stepTimeoutSeconds = 900;
    launcher.stepTimeoutGraceSeconds = 60;
    launcher.outputMaxChars = 65536;
    launcher.memoryLimit = "4g";
    launcher.pidsLimit = 2048;
    launcher.cpus = "2";
    launcher.artifactsRegistryHost = "qits-artifacts:8080";
    launcher.artifactsImageRepository = "qits";
    launcher.artifactsNpmHostedUrl = "http://qits-artifacts:8080/artifacts/npm/npm/";
    launcher.artifactsNpmProxyUrl = "http://qits-artifacts:8080/artifacts/npm/npmjs/";
    launcher.artifactsMavenRegistryUrl = "http://qits-artifacts:8080/artifacts/maven/maven";
    launcher.artifactsDocsUrl = "http://qits-artifacts:8080/artifacts/docs/docs";
    launcher.workspacesUrl = "http://qits-workspaces:8080";
    launcher.containers =
        new ContainersClient(url, Duration.ofSeconds(2), Duration.ofSeconds(5), TokenSource.none());
    launcher.bootReapPatience = Duration.ZERO;

    CiStepRelay relay = new CiStepRelay();
    relay.outputMaxChars = 65536;

    CiDaemonStepRunner runner = new CiDaemonStepRunner();
    runner.launcher = launcher;
    runner.registry = registry;
    runner.relay = relay;
    runner.registerTimeoutSeconds = 1;
    runner.initTimeoutSeconds = 1;
    runner.stepTimeoutGraceSeconds = 1;
    return runner;
  }

  private static StepSpec step() {
    return new StepSpec(
        RUN_ID,
        2,
        "repo-1",
        "main",
        "cafebabe",
        "maven:3.9",
        "echo hello",
        "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/deadbeef",
        900,
        false,
        Map.of());
  }

  /** A listener that records nothing: what a step emits is other tests' subject. */
  private static StepListener silent() {
    return new StepListener() {
      @Override
      public void onStarted() {}

      @Override
      public void onChunk(String text) {}

      @Override
      public void onFinished() {}
    };
  }

  /**
   * <b>A refused create is a step that never ran.</b> No container exists, so there is no log to
   * capture — what the orchestrator said is the whole account, and it is recorded as data about the
   * run rather than parsed for meaning.
   */
  @Test
  public void anOrchestratorRefusingTheCreateIsRecordedAsLaunchFailed() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(409, "{\"code\":\"IMAGE_MISSING\",\"message\":\"nothing published maven:3.9\"}");

      StepResult result = runner(stub.url(), new ScriptedRegistry()).run(step(), silent());

      assertEquals(StepOutcome.LAUNCH_FAILED, result.outcome());
      assertTrue(result.output().contains("IMAGE_MISSING"), result.output());
      assertTrue(result.output().contains("nothing published maven:3.9"), result.output());
    }
  }

  /**
   * <b>The log capture and the removal are one call, and the tail is the step's recorded output.</b>
   * This is the case the whole no-self-removal rule exists for: a container that never got as far as
   * a checkout has nothing but its own stdout to offer, and the ordering that used to be two calls
   * in a particular order is now the far side of one.
   */
  @Test
  public void aContainerThatNeverInitializedIsRemovedByTheCallThatReadsItsLog() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(201, "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"observed\":\"RUNNING\"}}")
          .script(200, "{\"existed\":true,\"logTail\":\"fatal: could not read Username\"}")
          // The unconditional reap in the runner's finally, finding nothing left.
          .fallback(200, "{\"existed\":false}");

      ScriptedRegistry registry = new ScriptedRegistry();
      registry.initialization =
          CiDaemonRegistry.Initialization.of(
              CiDaemonRegistry.Initialization.Status.NEVER_INITIALIZED);

      StepResult result = runner(stub.url(), registry).run(step(), silent());

      assertEquals(StepOutcome.NEVER_INITIALIZED, result.outcome());
      assertEquals("fatal: could not read Username", result.output());

      List<StubContainersServer.Received> calls = stub.received();
      assertEquals(3, calls.size(), "one create, one destroy-with-logs, one unconditional reap");
      assertEquals(DELETE_PATH, calls.get(1).path());
      assertEquals("volumes=false&logs=true", calls.get(1).query());
      assertEquals("volumes=false&logs=false", calls.get(2).query());
    }
  }

  /**
   * <b>The reap in the {@code finally} tolerates finding nothing, and that is what lets it stay
   * unconditional.</b> A branch that already removed the container to read its log reaches it too;
   * an absent place is exactly what was asked for, whether the service says so with an idempotent
   * 200 or something in front of it says so with a 404.
   */
  @Test
  public void theUnconditionalReapToleratesAContainerThatIsAlreadyGone() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(201, "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"observed\":\"RUNNING\"}}")
          .script(200, "{\"existed\":true,\"logTail\":\"the socket dropped\"}")
          .fallback(404, "{\"code\":\"404\",\"message\":\"Nothing is at dev-qits-ci/ci-step/…\"}");

      ScriptedRegistry registry = new ScriptedRegistry();
      registry.initialization =
          CiDaemonRegistry.Initialization.of(
              CiDaemonRegistry.Initialization.Status.CONNECTION_LOST);

      StepResult result = runner(stub.url(), registry).run(step(), silent());

      assertEquals(StepOutcome.CONNECTION_LOST, result.outcome());
      assertEquals("the socket dropped", result.output());
      assertEquals(3, stub.received().size(), "a 404 is a success and is not retried");
    }
  }
}
