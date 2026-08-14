package eu.wohlben.qits.ci.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.TokenSource;
import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * How the launcher reads what the orchestrator answers — the half of it that is HTTP rather than
 * spec assembly, driven against {@link StubContainersServer} on a real socket.
 *
 * <p><b>The claims here are all about the four answers, and each of them is a decision this class
 * makes rather than one the client makes for it.</b> A refusal and an unreachable service mean
 * opposite things to a run: one is evidence about the request and the other is evidence about
 * nothing at all, and the whole reason the client keeps them apart is so that a consumer can act on
 * the difference. What acting on it looks like is what this file pins.
 *
 * <p>Plain JUnit with fields set by hand, like {@code CiDaemonLauncherTest}: nothing here needs a
 * container, a database or an application.
 */
public class CiDaemonLauncherContainersTest {

  private static final String RUN_ID = "0123456789abcdef-run";

  /** What {@code containerName} makes of the fixture, and therefore the ref every call addresses. */
  private static final String CONTAINER = "qits-ci-01234567-412621e6-2";

  private final LaunchSpec spec =
      new LaunchSpec(
          RUN_ID,
          2,
          "repo-1",
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

  /**
   * A {@link TokenSource} that counts how often it was asked, so a test can say that a retry asks
   * for a FRESH bearer rather than replaying the one that was just refused. That is the whole
   * mechanism by which a launch survives an idp cutover: the client asks this source per request,
   * so a token minted after the cutover is picked up by retrying and by nothing else.
   */
  private static final class CountingTokens implements TokenSource {

    private final AtomicInteger asked = new AtomicInteger();

    @Override
    public Optional<String> bearer() {
      return Optional.of("token-" + asked.incrementAndGet());
    }

    int asked() {
      return asked.get();
    }
  }

  private final CountingTokens tokens = new CountingTokens();

  private CiDaemonLauncher launcher(String url) {
    return launcher(url, Duration.ZERO);
  }

  /** The same launcher, patient about the auth blip for a window a test can afford to wait out. */
  private CiDaemonLauncher launcher(String url, Duration launchPatience) {
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
    launcher.outputMaxChars = 64;
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
    // Deadlines a test can afford. The shipped ones are minutes, and they are about an image pull.
    launcher.containers =
        new ContainersClient(url, Duration.ofSeconds(2), Duration.ofSeconds(5), tokens);
    launcher.bootReapPatience = Duration.ZERO;
    launcher.launchPatience = launchPatience;
    return launcher;
  }

  /**
   * A window a test can wait out. The shipped one is PT90S with a five-second pause under it; the
   * pause is capped at the window, so this buys exactly one retry — which is the whole of what these
   * cases have to observe.
   *
   * <p><b>A second rather than a few milliseconds, because the window starts before the FIRST
   * attempt</b> — that is the shipped semantics, not a test artefact — and the first attempt of a
   * fresh {@code ContainersClient} pays for the JDK client's own setup. A window shorter than that
   * setup would make these cases about this machine's speed.
   */
  private static final Duration ONE_RETRY = Duration.ofSeconds(1);

  /** An address nothing listens on — the only honest way to stage "nothing answered". */
  private static final String NOTHING_ANSWERS = "http://127.0.0.1:1";

  // --- the launch ---------------------------------------------------------------------------------

  @Test
  public void aPlaceThatWasCreatedIsALaunchThatStarted() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(
          201,
          "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"desired\":\"RUNNING\",\"observed\":\"STARTING\"}}");

      CiDaemonLauncher.Launched launched = launcher(stub.url()).launch(spec);

      assertTrue(launched.started(), launched.error());
      assertEquals(CONTAINER, launched.containerName());
      // The place is owner/workload/ref, and the ref is the container's own name.
      assertEquals(
          "/containers/api/containers/dev-qits-ci/ci-step/" + CONTAINER, stub.last().path());
      assertEquals("PUT", stub.last().method());
    }
  }

  /**
   * <b>A 2xx whose container is not there is a failed launch.</b> The wire contract calls this a
   * true answer rather than a failed request, and it is right to — the row exists and carries what
   * docker said. It is still not a started container, and reading it as one would cost the run a
   * minute of a build slot waiting for a daemon that cannot dial, and then record NEVER_STARTED for
   * a container that never existed.
   */
  @Test
  public void aTwoHundredWhoseContainerIsMissingIsNotAStartedLaunch() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(
          200,
          "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"desired\":\"RUNNING\","
              + "\"observed\":\"MISSING\"},\"detail\":\"docker: invalid reference format\"}");

      CiDaemonLauncher.Launched launched = launcher(stub.url()).launch(spec);

      assertFalse(launched.started());
      assertTrue(launched.error().contains("invalid reference format"), launched.error());
    }
  }

  /**
   * A refusal that is about the REQUEST is recorded with the service's own word on it and is
   * <b>not</b> retried, patience window or no: this call sits on the run worker, an ensure may
   * already be pulling an image behind a long deadline, and no window makes an unpublished image
   * appear. The run is what gets attempted again.
   */
  @Test
  public void aRefusedCreateIsOneAttemptAndCarriesTheServicesOwnWord() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(409, "{\"code\":\"IMAGE_MISSING\",\"message\":\"nothing published maven:3.9\"}");

      CiDaemonLauncher.Launched launched = launcher(stub.url(), ONE_RETRY).launch(spec);

      assertFalse(launched.started());
      assertTrue(launched.error().contains("IMAGE_MISSING"), launched.error());
      assertTrue(launched.error().contains("nothing published maven:3.9"), launched.error());
      assertEquals(1, stub.received().size(), "a create the service answered about is not retried");
    }
  }

  /** The same claim for the other refusal a step container can earn — the spec changed under a ref. */
  @Test
  public void aSpecConflictIsOneAttemptEvenWithAPatienceWindow() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(409, "{\"code\":\"SPEC_CONFLICT\",\"message\":\"this ref runs another spec\"}");

      CiDaemonLauncher.Launched launched = launcher(stub.url(), ONE_RETRY).launch(spec);

      assertFalse(launched.started());
      assertTrue(launched.error().contains("SPEC_CONFLICT"), launched.error());
      assertEquals(1, stub.received().size(), "no window answers a conflict");
    }
  }

  @Test
  public void anUnreachableOrchestratorIsALaunchThatSaysSo() {
    CiDaemonLauncher.Launched launched = launcher(NOTHING_ANSWERS).launch(spec);

    assertFalse(launched.started());
    // Named as the network fact it is. A caller reading this as a refusal would conclude the
    // workload was refused; nothing was learned about the workload at all.
    assertTrue(launched.error().startsWith("orchestrator unreachable: "), launched.error());
  }

  // --- the idp-cutover window ---------------------------------------------------------------------

  /**
   * <b>The measured failure this loop exists for.</b> On the 2026-08-12 rebootstrap the deploy train
   * replaced qits-platform-idp and the next three push builds died at step launch with {@code
   * orchestrator refused: refused 401}, while every later one passed. A 401 is not a statement about
   * the request when the identity provider has just been replaced — it is a statement about the
   * moment — so the launch asks again, and <b>asks for a fresh bearer while doing so</b>, which is
   * the only way a post-cutover token can be picked up.
   */
  @Test
  public void anAuthBlipIsHeldThroughAndEachAttemptAsksForAFreshToken() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(401, "{\"code\":\"401\",\"message\":\"unauthorized\"}")
          .script(
              201,
              "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"desired\":\"RUNNING\","
                  + "\"observed\":\"STARTING\"}}");

      CiDaemonLauncher.Launched launched = launcher(stub.url(), ONE_RETRY).launch(spec);

      assertTrue(launched.started(), launched.error());
      assertEquals(2, stub.received().size(), "the blip is held through");
      assertEquals(2, tokens.asked(), "a retry asks the TokenSource again");
      assertEquals("Bearer token-2", stub.last().headers().get("Authorization"));
    }
  }

  /** A 403 from the owner guard is the same window read from the orchestrator's side of it. */
  @Test
  public void aForbiddenIsHeldThroughToo() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(403, "{\"code\":\"403\",\"message\":\"owner mismatch\"}")
          .script(
              200,
              "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"desired\":\"RUNNING\","
                  + "\"observed\":\"RUNNING\"}}");

      assertTrue(launcher(stub.url(), ONE_RETRY).launch(spec).started());
      assertEquals(2, stub.received().size());
    }
  }

  /**
   * <b>Past the window it is an ordinary failed launch, named as the auth refusal it is.</b> The
   * patience holds through a cutover; an owner that is simply wrong is not a cutover, and a run must
   * not sit on a build slot forever to find that out.
   */
  @Test
  public void aPersistentAuthRefusalEndsAsALaunchThatNamesIt() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(401, "{\"code\":\"401\",\"message\":\"unauthorized\"}");

      CiDaemonLauncher.Launched launched = launcher(stub.url(), ONE_RETRY).launch(spec);

      assertFalse(launched.started());
      assertTrue(launched.error().startsWith("orchestrator refused: "), launched.error());
      assertTrue(launched.error().contains("401"), launched.error());
      assertTrue(stub.received().size() >= 2, "the window was spent before giving up");
    }
  }

  /**
   * <b>Nothing answering is held through as well, and it is safe for a reason a {@code docker run}
   * never had.</b> An {@code ensure} is a PUT per (owner, workload, ref) and the ref is this step's
   * own container name, so a second attempt addresses the same place: a container the first attempt
   * created and could not report is adopted rather than duplicated.
   */
  @Test
  public void anOrchestratorThatWasRestartingIsAskedAgain() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.scriptSilence()
          .script(
              201,
              "{\"containerName\":\"" + CONTAINER + "\",\"state\":{\"desired\":\"RUNNING\","
                  + "\"observed\":\"STARTING\"}}");

      CiDaemonLauncher.Launched launched = launcher(stub.url(), ONE_RETRY).launch(spec);

      assertTrue(launched.started(), launched.error());
      assertEquals(2, stub.received().size());
      // The same place both times, which is what makes the second attempt an adoption.
      assertEquals(
          "/containers/api/containers/dev-qits-ci/ci-step/" + CONTAINER, stub.last().path());
    }
  }

  // --- the teardown that brings the log back ------------------------------------------------------

  @Test
  public void theLogTailAndTheRemovalAreOneCall() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, "{\"existed\":true,\"logTail\":\"qits-ci: neither wget nor curl\"}");

      assertEquals("qits-ci: neither wget nor curl", launcher(stub.url()).destroyWithLogs(CONTAINER));

      assertEquals(1, stub.received().size(), "one call, so nothing can lose the ordering");
      assertEquals("DELETE", stub.last().method());
      assertEquals("volumes=false&logs=true", stub.last().query());
    }
  }

  /**
   * <b>The bound is applied on this side too.</b> The orchestrator bounds what it returns and that
   * is not the point: this is the last untrusted boundary before the text becomes a row, and a bound
   * only the sender applies is a bound a buggy or hostile sender does not apply.
   */
  @Test
  public void theReturnedTailIsBoundedAgainstTheAnswerItself() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(200, "{\"existed\":true,\"logTail\":\"" + "x".repeat(5000) + "\"}");

      String tail = launcher(stub.url()).destroyWithLogs(CONTAINER);

      // qits.ci.output-max-chars, wired to 64 in this fixture.
      assertEquals(64, tail.length());
    }
  }

  @Test
  public void aTailThatCouldNotBeFetchedSaysSoAndFailsNothing() {
    String tail = launcher(NOTHING_ANSWERS).destroyWithLogs(CONTAINER);

    assertTrue(tail.startsWith("log tail unavailable: "), tail);
  }

  // --- the plain reap -----------------------------------------------------------------------------

  @Test
  public void aReapOfSomethingAlreadyGoneIsASuccess() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      // Both spellings of "it is not there": the service's own idempotent 200, and a 404 from
      // anything in front of it.
      stub.script(200, "{\"existed\":false}").script(404, "{\"code\":\"404\",\"message\":\"no\"}");

      launcher(stub.url()).reap(CONTAINER);
      launcher(stub.url()).reap(CONTAINER);

      assertEquals(2, stub.received().size(), "neither answer is retried");
      assertEquals("volumes=false&logs=false", stub.last().query());
    }
  }

  @Test
  public void aReapRetriesAFifthHundredAndThenSucceeds() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(503, "{\"code\":\"503\",\"message\":\"the database is down\"}")
          .script(200, "{\"existed\":true}");

      launcher(stub.url()).reap(CONTAINER);

      assertEquals(2, stub.received().size(), "a 5xx is the class another attempt could change");
    }
  }

  /**
   * <b>The same window, on the teardown.</b> A delete is idempotent, so the 401 an idp cutover
   * leaves behind is retried here exactly as it is on the launch — and a reap that gave up on it
   * would leave the orchestrator's {@code maxAge} GC to collect a container this process could have
   * removed at once.
   */
  @Test
  public void aReapRetriesAnAuthBlipAndThenSucceeds() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(401, "{\"code\":\"401\",\"message\":\"unauthorized\"}")
          .script(204, null);

      launcher(stub.url()).reap(CONTAINER);

      assertEquals(2, stub.received().size(), "an idempotent delete may be asked again");
      assertEquals(2, tokens.asked(), "and the second attempt asks for a fresh bearer");
    }
  }

  /**
   * <b>A reap failure never fails a green step.</b> The step is over and its result is recorded; the
   * container is the registry's own {@code maxAge} to collect. So this returns after its budget with
   * a WARN and nothing else — no throw, and no unbounded wait on the run worker.
   */
  @Test
  public void aReapThatCannotBeMadeGivesUpQuietly() {
    launcher(NOTHING_ANSWERS).reap(CONTAINER);
  }

  // --- the boot reap ------------------------------------------------------------------------------

  @Test
  public void theBootReapAsksForThisOwnersOwnStepContainersBeforeAnInstant() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.script(
          200,
          "{\"destroyed\":[{\"ref\":\"a\",\"removed\":true},{\"ref\":\"b\",\"removed\":true},"
              + "{\"ref\":\"c\",\"removed\":false,\"detail\":\"already gone\"}]}");
      Instant bootedAt = Instant.parse("2026-08-11T09:00:00Z");

      assertEquals(2, launcher(stub.url()).destroyAllOwned(bootedAt));

      // The scope, on the wire: this owner, this workload, and the instant that makes it a boot reap
      // rather than a purge of everything this owner has running.
      assertEquals("/containers/api/containers/dev-qits-ci/ci-step", stub.last().path());
      assertEquals("createdBefore=2026-08-11T09%3A00%3A00Z", stub.last().query());
      assertEquals("DELETE", stub.last().method());
    }
  }

  /**
   * <b>Boot proceeds.</b> An orchestrator that is not up yet is waited for a patience window and
   * then given up on, because a process that refused to start because a teardown could not run is a
   * process that cannot recover the runs it is holding. The orphans are then the registry's GC's.
   */
  @Test
  public void aBootReapThatCannotBeMadeWarnsAndLetsTheProcessBoot() {
    assertEquals(0, launcher(NOTHING_ANSWERS).destroyAllOwned(Instant.now()));
  }

  /** The same, for a service that is up and answering 5xx: still an answer this boot cannot use. */
  @Test
  public void aBootReapTheServiceRefusesIsAlsoJustAWarning() throws Exception {
    try (StubContainersServer stub = new StubContainersServer()) {
      stub.fallback(500, "{\"code\":\"500\",\"message\":\"nope\"}");

      assertEquals(0, launcher(stub.url()).destroyAllOwned(Instant.now()));
      assertEquals(List.of(), stub.received().subList(1, stub.received().size()),
          "the patience window is zero in this fixture, so it is one attempt");
    }
  }
}
