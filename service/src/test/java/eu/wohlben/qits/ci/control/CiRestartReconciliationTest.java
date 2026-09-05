package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.TokenSource;
import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The fail-and-reap reconciliation, both halves at once.</b> A run is mid-step — its row {@code
 * RUNNING}, its step container up — and the process dies with no shutdown path running at all. What
 * the next boot owes that run is two things that only mean something together: no row claims to be
 * executing, and nothing those rows started is still on the host. {@code CiRunService.onStart} does
 * the first, {@code CiDaemonLauncher.onStart} the second.
 *
 * <p>Both observers skip {@code LaunchMode.TEST}, so this drives what they call — {@link
 * CiRunService#sweepInterrupted} and {@link CiDaemonLauncher#destroyAllOwned} — exactly as {@code
 * CiQueuedRunTest} drives the sweep.
 *
 * <p><b>It used to need docker, and it does not any more.</b> Its predecessor
 * ({@code CiRestartReconciliationIT}, tagged {@code extended}) really started containers, really
 * reaped them, and asserted that an unlabelled bystander survived — which was the whole of what the
 * host-wide {@code qits.ci.run} filter was for. There is no host-wide filter left to prove
 * something about: the reap asks the orchestrator for <em>this owner's</em> rows, so a bystander is
 * not a container the call could reach even in principle. The bystander proof moved to the
 * orchestrator's own suite, against real docker, where it belongs (qits-containers'
 * {@code ContainersRestartAdoptionIT}); what is qits-ci's own is the two halves running in the right
 * order and asking for the right scope, and that is provable against a socket.
 *
 * <p><b>The launcher is the injected bean with its client swapped, not a hand-wired one.</b> The
 * claim is about the production path — the owner it sends, the workload it scopes to — so
 * everything except the address is the deployment's own configuration. The client goes back in a
 * {@code finally}, because this application outlives the test method.
 *
 * <p><b>It lives in {@code control} rather than beside the launcher in {@code daemonhost}</b>
 * because {@link CiRunService#sweepInterrupted} is package-private there by design — the suite is
 * meant to drive it, and no other module may. Everything this test needs from the launcher is
 * public. Reversing the placement would cost the paired assertion, which is the whole point of the
 * test.
 */
@QuarkusTest
public class CiRestartReconciliationTest {

  @Inject CiDaemonLauncher launcher;

  @Inject CiRunService service;

  @Inject CiRunRepository runs;

  /**
   * <b>Reap, then sweep — and both halves of what each owes.</b> The reap asks for this owner's own
   * step containers created before the instant this boot began; the sweep then fails the push run
   * that was executing and re-queues the event run, which is the recovery that only the row can
   * make.
   */
  @Test
  public void aHardRestartReapsThisOwnersStepContainersAndSettlesTheRowsTheyBelongedTo()
      throws Exception {
    String pushRunId = UUID.randomUUID().toString();
    String eventRunId = UUID.randomUUID().toString();
    insertRunningPushRun(pushRunId);
    insertRunningEventRun(eventRunId);

    try (StubOrchestrator stub = new StubOrchestrator()) {
      Instant bootedAt = Instant.parse("2026-08-11T09:00:00Z");
      ContainersClient replaced = swapClient(stub.url());
      try {
        // The order a restart runs them in: reap first, then sweep. That order is the observers'
        // own @Priority pair (BootReconciliationOrderTest holds it) and the reason survives the
        // cutover — the sweep hands work back to the run worker, which asks for step containers at
        // once, and a reap running second could remove one of them. `createdBefore` is the second
        // net under that, not a replacement for it. Keep these two calls this way round.
        int reaped = launcher.destroyAllOwned(bootedAt);
        service.sweepInterrupted();
        service.awaitIdle();

        assertEquals(2, reaped);

        // The scope, on the wire. `owner` is this deployment's own name — the string the machine
        // token's `sub` has to equal once the gate is on — and the workload is what tells a step
        // container from anything else this service might one day ask for.
        StubOrchestrator.Received call = stub.last();
        assertEquals("DELETE", call.method());
        assertTrue(call.path().endsWith("/ci-step"), call.path());
        assertTrue(
            call.path().startsWith("/containers/api/containers/"), call.path());
        assertEquals("createdBefore=2026-08-11T09%3A00%3A00Z", call.query());
      } finally {
        restoreClient(replaced);
      }

      forgetLoadedEntities();

      // A push run may not be repeated: arbitrary push work is not safe to run twice.
      CiRun push = runs.findById(pushRunId);
      assertEquals(CiRunStatus.FAILED, push.status, "no row may still claim to be executing");
      assertNotNull(push.finishedAt);

      // An event run is restarted from its own immutable snapshot, which is what makes an
      // event-trigger script an at-least-once boundary. The sweep writes it QUEUED and hands it
      // back to the worker in the same breath, so what is observable after awaitIdle is the run
      // having been RE-EXECUTED — a stronger statement than the intermediate status, and the one
      // that says the snapshot was enough to recover from. Its pipeline declares no steps, so it
      // needs no container to reach an outcome.
      CiRun event = runs.findById(eventRunId);
      assertEquals(CiRunStatus.SUCCESS, event.status, "an interrupted event run is re-run, not failed");
      assertNotNull(event.finishedAt);
    } finally {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                runs.deleteById(pushRunId);
                runs.deleteById(eventRunId);
              });
    }
  }

  // --- the rows a dead process would have left behind ------------------------------------------

  /**
   * A leftover push run caught mid-step: claimed by a predecessor's worker, pinned to a daemon,
   * never ended. Per-push CI retired on 2026-09-05 so no live deployment writes such a row, and a
   * successor still has to say something honest about the ones already in its database — which is
   * FAILED, because its step died with its process and this engine has no worker that could replay
   * repository-authored work even if replaying it were safe.
   */
  private void insertRunningPushRun(String runId) {
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
              run.configPath = ".config/qits/ci-post-receive.yml";
              runs.persist(run);
            });
  }

  /** An event-triggered one, carrying the snapshot that is what makes it re-runnable. */
  private void insertRunningEventRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = new CiRun();
              run.id = runId;
              run.repoId = "restart-reconciliation-repo";
              run.branch = "main";
              run.commitSha = "d".repeat(40);
              run.status = CiRunStatus.RUNNING;
              run.createdAt = Instant.now();
              run.triggerType = CiTriggerType.EVENT;
              run.daemonVersion = "dead-daemon";
              run.configPath = ".config/qits/ci-event-restart.yml";
              run.triggerEventId = UUID.randomUUID().toString();
              run.triggerEventName = "SoftwareRelease";
              run.triggerEventOccurredAt = Instant.now();
              run.triggerEventPayload = "{}";
              run.triggerConfig = "event: SoftwareRelease\nsteps: []\n";
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

  // --- pointing the injected launcher at a socket this test owns -------------------------------

  /**
   * Swap the client on the launcher <b>bean</b>, past its CDI proxy: the field is the bean's and a
   * write through the proxy would land nowhere. Same unwrap {@code CiDaemonGateIT} does for the one
   * value that cannot be known before the application boots, and the same caveat — not a pattern to
   * spread.
   */
  private ContainersClient swapClient(String url) throws Exception {
    CiDaemonLauncher bean = (CiDaemonLauncher) ClientProxy.unwrap(launcher);
    var field = CiDaemonLauncher.class.getDeclaredField("containers");
    field.setAccessible(true);
    ContainersClient previous = (ContainersClient) field.get(bean);
    field.set(
        bean, new ContainersClient(url, Duration.ofSeconds(2), Duration.ofSeconds(5), TokenSource.none()));
    return previous;
  }

  private void restoreClient(ContainersClient previous) throws Exception {
    CiDaemonLauncher bean = (CiDaemonLauncher) ClientProxy.unwrap(launcher);
    var field = CiDaemonLauncher.class.getDeclaredField("containers");
    field.setAccessible(true);
    field.set(bean, previous);
  }

  /**
   * A qits-containers that answers one destroy-all and records what was asked.
   *
   * <p>A third small copy of the same shape (the launcher tests have their own in {@code
   * daemonhost}, and the client jar has the original): the packages differ and this one needs to be
   * nothing but a socket with one canned body on it. Duplicated rather than made visible across
   * packages, which is the same trade both {@code FakeCiStepRunner}s are.
   */
  private static final class StubOrchestrator implements AutoCloseable {

    record Received(String method, String path, String query) {}

    private final com.sun.net.httpserver.HttpServer server;

    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

    StubOrchestrator() throws IOException {
      server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              exchange.getRequestBody().readAllBytes();
              received.add(
                  new Received(
                      exchange.getRequestMethod(),
                      exchange.getRequestURI().getRawPath(),
                      exchange.getRequestURI().getRawQuery()));
              byte[] out =
                  "{\"destroyed\":[{\"ref\":\"a\",\"removed\":true},{\"ref\":\"b\",\"removed\":true}]}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, out.length);
              exchange.getResponseBody().write(out);
            }
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    Received last() {
      List<Received> all = List.copyOf(received);
      if (all.isEmpty()) {
        throw new AssertionError("nothing reached the stub");
      }
      return all.getLast();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
