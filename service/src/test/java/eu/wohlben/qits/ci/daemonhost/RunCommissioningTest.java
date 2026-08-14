package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import eu.wohlben.qits.ci.idp.RunCommissions;
import eu.wohlben.qits.ci.idp.StubIdp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The per-run push credential, end to end on this side of the wire: a real {@link StubIdp} on a real
 * socket, the real {@link CiDaemonLauncher} assembling a real spec from what it minted, and the real
 * {@link CiDaemonStepRunner#runClosed} giving it back.
 *
 * <p><b>Why it is its own class.</b> {@code CiDaemonLauncherTest} is spec assembly with no
 * collaborator that sends anything, and it stays that way; what is under test here is the
 * <em>lifecycle</em> — one commission per run rather than per step, a second run's own, a failure
 * that fails the step, and a close that deletes.
 *
 * <p><b>Plain JUnit, no Quarkus.</b> The launcher takes its config in fields, the commissioner takes
 * its own the same way, and {@code StubIdp} hands out a wired pair because those fields are
 * package-private in another package. Nothing here needs an application.
 */
public class RunCommissioningTest {

  private static final String RUN = "0123456789abcdef-run";

  /** Short enough that the failure case's whole retry window is a fraction of a second. */
  private static final Duration PATIENCE = Duration.ofMillis(200);

  private StubIdp idp;

  @BeforeEach
  void startStub() {
    idp = new StubIdp();
  }

  @AfterEach
  void stopStub() {
    idp.close();
  }

  private CiDaemonLauncher launcher(RunCommissions commissions) {
    CiDaemonLauncher launcher = new CiDaemonLauncher();
    launcher.owner = "dev-qits-ci";
    launcher.network = "qits-net";
    launcher.containerGitUrl = "http://qits-githost:8080/";
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
    launcher.launchPatience = Duration.ZERO;
    launcher.commissions = commissions;
    return launcher;
  }

  /** One step of a run, publishing or not. */
  private static LaunchSpec step(String runId, int index, boolean docker) {
    return new LaunchSpec(
        runId,
        index,
        "repo-1",
        "main",
        "cafebabe",
        "maven:3.9",
        "daemon-7",
        "s3cr3t",
        "http://qits-artifacts:8080/artifacts/daemons/deadbeef",
        0,
        docker,
        "",
        Map.of());
  }

  @Test
  public void oneRunCommissionsOnceAndEveryLaterDockerStepReusesIt() {
    CiDaemonLauncher launcher = launcher(idp.runCommissions(PATIENCE));

    Map<String, String> first = launcher.buildWorkloadSpec(step(RUN, 1, true)).spec().env();
    Map<String, String> second = launcher.buildWorkloadSpec(step(RUN, 2, true)).spec().env();

    // The credential belongs to the RUN, not to the step: one commission, and the second step is
    // handed the same pair. One per step would be N clients to leak instead of one.
    assertEquals(1, idp.posted.size(), "one commission for the whole run");
    assertEquals("run-client-1", first.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals(first.get("QITS_COMMISSIONED_CLIENT_ID"), second.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals(
        first.get("QITS_COMMISSIONED_CLIENT_SECRET"), second.get("QITS_COMMISSIONED_CLIENT_SECRET"));
  }

  @Test
  public void aPlainStepCommissionsNothingAtAll() {
    CiDaemonLauncher launcher = launcher(idp.runCommissions(PATIENCE));

    Map<String, String> env = launcher.buildWorkloadSpec(step(RUN, 0, false)).spec().env();

    // Scoped exactly as the static credential was: only a step holding the socket can push, so only
    // that step is worth a credential — and a pipeline of plain test steps asks qits-idp for
    // nothing.
    assertEquals(List.of(), idp.posted);
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_ID"));
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_SECRET"));
    assertFalse(env.containsKey("DOCKER_CONFIG"));
    assertFalse(env.containsKey("QITS_CI_REGISTRY_AUTH_CONFIG"));
  }

  @Test
  public void theCommissionedPairIsBothTheDockerConfigAndTheTwoVariables() {
    Map<String, String> env =
        launcher(idp.runCommissions(PATIENCE)).buildWorkloadSpec(step(RUN, 1, true)).spec().env();

    // The document, byte for byte: the CLI's own base64 of id:secret, against the same address the
    // step reads as $QITS_REGISTRY, in a directory under /tmp rather than in the checkout.
    String auth =
        Base64.getEncoder()
            .encodeToString("run-client-1:run-s3cr3t-1".getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "{\"auths\":{\"qits-artifacts:8080\":{\"auth\":\"" + auth + "\"}}}",
        env.get("QITS_CI_REGISTRY_AUTH_CONFIG"));
    assertEquals("/tmp/qits-ci-registry-auth", env.get("DOCKER_CONFIG"));
    assertEquals("qits-artifacts:8080", env.get("QITS_REGISTRY"));

    // And the pair itself, which is what a BuildKit secret mount consumes:
    // --secret id=…,env=QITS_COMMISSIONED_CLIENT_SECRET writes no layer, where a --build-arg would.
    assertEquals("run-client-1", env.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertEquals("run-s3cr3t-1", env.get("QITS_COMMISSIONED_CLIENT_SECRET"));
  }

  @Test
  public void theCommissioningCallSaysWhatItIsForAndWhoIsAsking() {
    launcher(idp.runCommissions(PATIENCE)).buildWorkloadSpec(step(RUN, 1, true));

    // The context is what makes the reconciliation possible at all: a row qits-idp holds says which
    // run owns it, so a row whose run is over is reapable without any bookkeeping of ours.
    assertEquals(
        List.of("{\"contextKind\":\"ci-run\",\"contextId\":\"" + RUN + "\"}"), idp.posted);
    // And the caller is the service's OWN oidc client — a commissioned one is refused 403, which is
    // what keeps the tree one level deep.
    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    (StubIdp.SERVICE_CLIENT_ID + ":" + StubIdp.SERVICE_SECRET)
                        .getBytes(StandardCharsets.UTF_8));
    assertEquals(List.of(expected), idp.authorizations);
  }

  @Test
  public void aSecondRunGetsACredentialOfItsOwn() {
    CiDaemonLauncher launcher = launcher(idp.runCommissions(PATIENCE));

    Map<String, String> first = launcher.buildWorkloadSpec(step("run-a", 1, true)).spec().env();
    Map<String, String> second = launcher.buildWorkloadSpec(step("run-b", 1, true)).spec().env();

    assertEquals(2, idp.posted.size());
    assertNotEquals(
        first.get("QITS_COMMISSIONED_CLIENT_ID"), second.get("QITS_COMMISSIONED_CLIENT_ID"));
    assertTrue(idp.posted.get(0).contains("run-a"));
    assertTrue(idp.posted.get(1).contains("run-b"));
  }

  @Test
  public void aCommissionThatCouldNotBeMadeFailsTheStepAndNamesTheCall() {
    idp.mintStatus = 503;
    CiDaemonLauncher launcher = launcher(idp.runCommissions(PATIENCE));

    // No container client is wired, so a launch that reached one would NPE: this returning at all is
    // the assertion that nothing was started. Which is the posture — launching credential-less would
    // turn an idp blip into a push 401 minutes later, inside somebody's build.
    CiDaemonLauncher.Launched launched = launcher.launch(step(RUN, 1, true));

    assertFalse(launched.started());
    assertTrue(launched.error().startsWith("could not commission a per-run credential"), launched.error());
    assertTrue(launched.error().contains("/idp/api/clients"), launched.error());
    assertTrue(launched.error().contains("contextKind=ci-run"), launched.error());
    assertTrue(launched.error().contains("503"), launched.error());
    // Bounded retry rather than one attempt: a 5xx and a 401 are about the moment, so the window is
    // asked more than once and then gives up rather than holding a build slot forever.
    assertTrue(idp.posted.size() >= 2, "asked again inside the window: " + idp.posted.size());
  }

  @Test
  public void aRefusalAboutTheRequestIsOneAttempt() {
    // 403 is qits-idp saying a commissioned client may not commission — a statement about the
    // request that no window fixes, so the step fails at once rather than after the patience.
    idp.mintStatus = 403;

    CiDaemonLauncher.Launched launched =
        launcher(idp.runCommissions(PATIENCE)).launch(step(RUN, 1, true));

    assertFalse(launched.started());
    assertEquals(1, idp.posted.size());
    assertTrue(launched.error().contains("403"), launched.error());
  }

  @Test
  public void closingTheRunGivesTheCredentialBack() {
    RunCommissions commissions = idp.runCommissions(PATIENCE);
    launcher(commissions).buildWorkloadSpec(step(RUN, 1, true));

    CiStepRelay relay = new CiStepRelay();
    relay.outputMaxChars = 65536;
    CiDaemonStepRunner runner = new CiDaemonStepRunner();
    runner.relay = relay;
    runner.commissions = commissions;

    runner.runClosed(RUN);

    assertEquals(List.of("run-client-1"), idp.deleted);
    // And it is gone from memory too, so a run id that came round again would commission afresh
    // rather than hand out a credential qits-idp no longer knows.
    assertFalse(commissions.holds("run-client-1"));
  }

  @Test
  public void closingARunThatNeverPublishedDeletesNothing() {
    RunCommissions commissions = idp.runCommissions(PATIENCE);
    launcher(commissions).buildWorkloadSpec(step(RUN, 0, false));

    CiStepRelay relay = new CiStepRelay();
    relay.outputMaxChars = 65536;
    CiDaemonStepRunner runner = new CiDaemonStepRunner();
    runner.relay = relay;
    runner.commissions = commissions;

    runner.runClosed(RUN);

    assertEquals(List.of(), idp.deleted);
  }

  @Test
  public void aDeploymentWithNoOidcClientCommissionsNothingAndInjectsNothing() {
    // The fallback arm, and it must be byte-identical to what shipped before per-run credentials
    // existed: quarkus.oidc-client.client-enabled is false out of the box, so there is nothing to
    // commission with and a step container's environment gains nothing at all.
    Map<String, String> env =
        launcher(StubIdp.disabledCommissions()).buildWorkloadSpec(step(RUN, 1, true)).spec().env();

    assertEquals(List.of(), idp.posted);
    assertFalse(env.containsKey("DOCKER_CONFIG"));
    assertFalse(env.containsKey("QITS_CI_REGISTRY_AUTH_CONFIG"));
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_ID"));
    assertFalse(env.containsKey("QITS_COMMISSIONED_CLIENT_SECRET"));
    // The BuildKit pair is not a credential and rides along regardless.
    assertEquals("1", env.get("DOCKER_BUILDKIT"));
  }

  @Test
  public void theSecretIsNeverASubstringOfAnythingSentBesideItsOwnVariable() {
    var request = launcher(idp.runCommissions(PATIENCE)).buildWorkloadSpec(step(RUN, 1, true));
    String secret = "run-s3cr3t-1";

    // It reaches the container in exactly two forms: base64 inside the docker document, and raw
    // under one name a BuildKit secret mount reads. Anywhere else — a second variable, an argv, a
    // label, the bootstrap — is a leak into something that gets logged or baked into an image.
    List<String> carrying =
        request.spec().env().entrySet().stream()
            .filter(entry -> entry.getValue().contains(secret))
            .map(Map.Entry::getKey)
            .toList();
    assertEquals(List.of("QITS_COMMISSIONED_CLIENT_SECRET"), carrying);
    assertFalse(String.valueOf(request.spec().args()).contains(secret));
    assertFalse(String.valueOf(request.spec().entrypoint()).contains(secret));
    assertFalse(String.valueOf(request.spec().extraLabels()).contains(secret));
    assertFalse(String.valueOf(request.spec().explicitName()).contains(secret));
    assertFalse(CiDaemonLauncher.BOOTSTRAP.contains(secret));
  }
}
