package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher;
import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher.LaunchSpec;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>The fail-and-reap reconciliation, both halves at once.</b> A run is mid-step — its row {@code
 * RUNNING}, its step container up — and the process dies with no shutdown path running at all. What
 * the next boot owes that run is two things that only mean something together: no row claims to be
 * executing, and nothing those rows started is still on the host. {@code CiRunService.onStart} does
 * the first, {@code CiDaemonLauncher.onStart} the second, and until this test the second half was
 * verified by reading a log line.
 *
 * <p>Both observers skip {@code LaunchMode.TEST}, so this drives what they call — {@link
 * CiRunService#sweepInterrupted} and {@link CiDaemonLauncher#reapOrphans} — exactly as {@code
 * CiQueuedRunTest} drives the sweep and {@code CiDaemonContainerProbeIT} reaches past the probe's
 * own test-mode guard. The launcher is the <b>injected</b> bean rather than a hand-wired one, for
 * {@code CiDaemonGateIT}'s reason: the boot sweep runs against the deployment's configured runtime
 * and network, which is the thing being claimed here.
 *
 * <p><b>It lives in {@code control} rather than beside the launcher in {@code daemonhost}</b>
 * because {@link CiRunService#sweepInterrupted} is package-private there by design — the suite is
 * meant to drive it, and no other module may. Everything this test needs from the launcher is
 * public; the one thing that is not, the {@code qits.ci.run} label, is restated as a literal below
 * and pinned in the launcher's own argv assertion. Reversing the placement would cost the paired
 * assertion, which is the whole point of the test.
 *
 * <p><b>Do not run this against a host with a live qits-ci on it.</b> The boot sweep is
 * unconditional by construction — it removes <em>every</em> container carrying the run label,
 * because after a crash there is no way to tell a container this process started from one its
 * predecessor did — so a real platform's in-flight step would be reaped along with the fixture's.
 * That is correct at boot and destructive mid-run, which is why this is tagged {@code extended} and
 * why the count below is asserted as a floor rather than exactly.
 *
 * <p>Needs docker and the image; no daemon binary, no git host and no route back to this JVM, so it
 * is the cheapest docker-backed IT here. Run with {@code -DskipITs=false}; excluded from {@code
 * -Dnative} like every other one.
 */
@QuarkusTest
@Tag("extended")
public class CiRestartReconciliationIT {

  /** Only a shell is wanted: nothing clones here, and no daemon binary is ever fetched. */
  private static final String IMAGE = "alpine:3";

  private static final String RUNTIME = System.getProperty("qits.ci.container-runtime", "docker");

  /**
   * The label every step container carries and the boot sweep filters on. Spelled out because {@code
   * CiDaemonLauncher.RUN_LABEL} is package-private to {@code daemonhost}; the constant it duplicates
   * is asserted literally in the launcher's own argv test, so the two cannot drift silently.
   */
  private static final String RUN_LABEL = "qits.ci.run";

  /**
   * Refused at once, so the bootstrap gives up and the container is an exited orphan within a
   * second — which is what a step container really looks like after this host lost its qits-ci: the
   * daemon self-terminates when its control socket goes, so the reap usually finds a corpse.
   */
  private static final String UNREACHABLE_BINARY = "http://127.0.0.1:1/qits-ci-daemon";

  @Inject CiDaemonLauncher launcher;

  @Inject CiRunService service;

  @Inject CiRunRepository runs;

  @Test
  public void aHardRestartReapsTheOrphanedStepContainersAndFailsTheRunTheyBelongedTo()
      throws Exception {
    assumeTrue(dockerAndImageAvailable(), "docker + " + IMAGE + " required for this IT");

    String runId = UUID.randomUUID().toString();
    launcher.ensureNetwork();

    // The container the dead process launched, through the production path, so the label under test
    // is the one the launcher really writes.
    CiDaemonLauncher.Launched launched =
        launcher.launch(
            new LaunchSpec(
                runId,
                0,
                "restart-reconciliation-repo",
                "main",
                "c".repeat(40),
                IMAGE,
                "daemon-" + runId,
                "s3cr3t",
                UNREACHABLE_BINARY,
                false,
                Map.of()));
    assertTrue(launched.started(), "docker refused the launch: " + launched.error());

    // And one still executing: a restart quick enough to beat the daemon's own exit leaves this, and
    // it is the state `docker rm -f` — rather than a plain `rm` — exists for.
    String stillRunning = "qits-ci-it-running-" + UUID.randomUUID();
    exec(RUNTIME, "run", "-d", "--label", RUN_LABEL + "=" + runId, "--name", stillRunning, IMAGE,
        "sleep", "300");

    // Something on this host that is not a CI step at all. The sweep is label-filtered, and an
    // unfiltered one would take the platform's own containers with it.
    String bystander = "qits-ci-it-bystander-" + UUID.randomUUID();
    exec(RUNTIME, "run", "-d", "--name", bystander, IMAGE, "sleep", "300");

    // The row that container was executing. It stays RUNNING because nothing graceful ran.
    insertRunningRun(runId);

    try {
      // The two boot observers, as a restart runs them.
      int reaped = launcher.reapOrphans();
      service.sweepInterrupted();
      service.awaitIdle();

      assertTrue(reaped >= 2, "both labelled containers should have been reaped, got " + reaped);
      assertFalse(
          containerExists(launched.containerName()),
          "the exited step container a crash left behind must be gone");
      assertFalse(containerExists(stillRunning), "a step container still running must be gone too");
      assertTrue(
          containerExists(bystander),
          "the sweep must remove CI step containers and nothing else");

      forgetLoadedEntities();
      CiRun swept = runs.findById(runId);
      assertEquals(CiRunStatus.FAILED, swept.status, "no row may still claim to be executing");
      assertNotNull(swept.finishedAt);

      // A second boot has nothing left to do: the reconciliation is the fixed point it claims to be.
      assertEquals(0, launcher.reapOrphans());
    } finally {
      exec(RUNTIME, "rm", "-f", bystander);
      QuarkusTransaction.requiringNew().run(() -> runs.deleteById(runId));
    }
  }

  // --- rows and containers a dead process would have left behind ------------------------------

  /** A push-triggered run caught mid-step: claimed by the worker, pinned to a daemon, never ended. */
  private void insertRunningRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = "restart-reconciliation-repo";
              run.branch = "main";
              run.commitSha = "c".repeat(40);
              run.status = CiRunStatus.RUNNING;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.POST_RECEIVE;
              run.daemonVersion = "dead-daemon";
              run.configPath = CiConfigParser.CONFIG_PATH;
              runs.persist(run);
            });
  }

  /**
   * Drop what this thread has already loaded, so the read after the sweep really goes to the
   * database rather than to the identity map holding the row as it was inserted.
   */
  private void forgetLoadedEntities() {
    runs.getEntityManager().clear();
  }

  private boolean dockerAndImageAvailable() {
    try {
      return new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean containerExists(String name) throws Exception {
    return new ProcessBuilder(RUNTIME, "container", "inspect", name).start().waitFor() == 0;
  }

  private static void exec(String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new RuntimeException(String.join(" ", argv) + " failed:\n" + out);
    }
  }
}
