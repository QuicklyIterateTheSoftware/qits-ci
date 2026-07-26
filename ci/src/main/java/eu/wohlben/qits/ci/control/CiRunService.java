package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiConfigSource.ConfigLookup;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.error.NotFoundException;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The pipeline orchestrator: a post-receive event → read the config from the pushed commit → run
 * its steps sequentially → record per-step pass/fail. Runs execute on a single-threaded daemon
 * worker (the intake returns immediately; runs across all repos are serialized — parallelism is an
 * explicit follow-up), with each DB transition in its own {@link QuarkusTransaction#requiringNew()}
 * bracket so the slow container/git work never holds a transaction (worker threads have no request
 * context; the {@code BlobService}/{@code GitHostRoutes} stance).
 *
 * <p>Recording semantics — a run is only ever recorded when it says something true about a commit:
 *
 * <ul>
 *   <li>no config file ⇒ nothing (opt-in);
 *   <li>git host unreachable ⇒ nothing, warn-logged (a read failure must not invent a gate);
 *   <li>commit no longer reachable (force-pushed away) ⇒ nothing, including when the discovery
 *       happens later, in a step's clone — the push it belonged to no longer exists, so a red run
 *       would blame a commit whose build was never broken;
 *   <li>config present but broken ⇒ {@link CiRunStatus#CONFIG_ERROR}, so the broken gate is
 *       visible;
 *   <li>config present with no steps ⇒ a trivially green run.
 * </ul>
 */
@ApplicationScoped
public class CiRunService {

  private static final Logger LOG = Logger.getLogger(CiRunService.class);

  static final String TRUNCATION_MARKER = "[... output truncated ...]\n";

  @Inject CiConfigSource configSource;
  @Inject CiConfigParser parser;
  @Inject CiStepRunner runner;
  @Inject CiRunRepository runs;
  @Inject CiStepRepository steps;

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "ci-run-worker");
            t.setDaemon(true);
            return t;
          });

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  /**
   * A run left {@code RUNNING} by a crash or a kill can never make progress — the worker queue does
   * not survive the JVM — so it would show as forever-executing. Fail those once at startup.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      int swept =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<CiRun> orphans = runs.list("status = ?1", CiRunStatus.RUNNING);
                    for (CiRun orphan : orphans) {
                      failIncompleteSteps(orphan.id);
                      orphan.status = CiRunStatus.FAILED;
                      orphan.finishedAt = Instant.now();
                    }
                    return orphans.size();
                  });
      if (swept > 0) {
        LOG.infof("Marked %d CI run(s) left RUNNING by a previous shutdown as FAILED", swept);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted CI runs at startup");
    }
  }

  /** The async entry the event intake calls — returns immediately, the run executes queued. */
  public void onPostReceive(String repoId, String branch, String oldSha, String newSha) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);
    CiIdentifiers.requireSha(newSha);
    worker.submit(
        () -> {
          try {
            execute(repoId, branch, newSha);
          } catch (RuntimeException e) {
            LOG.errorf(e, "CI run for %s@%s (%s) failed unexpectedly", repoId, branch, newSha);
          }
        });
  }

  /** The synchronous run — package-private so tests drive it without the worker. */
  void execute(String repoId, String branch, String sha) {
    ConfigLookup lookup = configSource.read(repoId, branch, sha);
    switch (lookup.status()) {
      case ABSENT -> {
        LOG.debugf("No %s at %s@%s — no CI run", CiConfigParser.CONFIG_PATH, repoId, sha);
        return;
      }
      case GONE -> {
        LOG.infof("Commit %s is no longer reachable in %s — no CI run recorded", sha, repoId);
        return;
      }
      case UNREACHABLE -> {
        LOG.warnf("Could not fetch %s from the git host — no CI run recorded for %s", sha, repoId);
        return;
      }
      case INVALID -> {
        LOG.infof("CI config unusable at %s@%s: %s", repoId, sha, lookup.message());
        persistRun(repoId, branch, sha, CiRunStatus.CONFIG_ERROR, List.of());
        return;
      }
      case FOUND -> {
        /* fall through to parse + run */
      }
    }

    CiPipeline pipeline;
    try {
      pipeline = parser.parse(lookup.content());
    } catch (CiConfigException e) {
      LOG.infof("CI config error at %s@%s: %s", repoId, sha, e.getMessage());
      persistRun(repoId, branch, sha, CiRunStatus.CONFIG_ERROR, List.of());
      return;
    }

    CiRun run = persistRun(repoId, branch, sha, CiRunStatus.RUNNING, pipeline.steps());
    try {
      runSteps(run, pipeline);
    } catch (RuntimeException e) {
      LOG.errorf(e, "CI run %s failed unexpectedly", run.id);
      // Never leave a step claiming to still be executing under a finished run.
      QuarkusTransaction.requiringNew().run(() -> failIncompleteSteps(run.id));
      finishRun(run.id, CiRunStatus.FAILED);
    }
  }

  private void runSteps(CiRun run, CiPipeline pipeline) {
    List<CiStep> pending =
        QuarkusTransaction.requiringNew().call(() -> steps.listByRunIdOrdered(run.id));
    boolean failed = false;
    for (CiStep step : pending) {
      if (failed) {
        updateStep(step.id, CiStepStatus.SKIPPED, null, null);
        continue;
      }
      updateStep(step.id, CiStepStatus.RUNNING, null, null);
      CiPipeline.CiStepDecl decl = pipeline.steps().get(step.stepIndex);
      CiStepRunner.StepResult result =
          runner.run(
              new CiStepRunner.StepSpec(
                  run.id, step.stepIndex, run.repoId, run.commitSha, decl.image(), decl.script()));

      // The script never ran: /workspace could not be produced. Two very different causes, so ask
      // git which it was rather than guessing — the commit may have been force-pushed away since
      // the
      // config read (this run describes a push that no longer exists ⇒ discard it), or the step's
      // image may simply lack git/bash or the host be unreachable (a real, user-visible failure
      // that
      // must stay on the record with its prelude error).
      if (!result.workspaceReady() && !result.timedOut()) {
        boolean commitGone =
            configSource.read(run.repoId, run.branch, run.commitSha).status()
                == ConfigLookup.Status.GONE;
        if (commitGone) {
          LOG.infof(
              "CI run %s: %s is no longer reachable — discarding the run", run.id, run.commitSha);
          discardRun(run.id);
          return;
        }
        LOG.infof(
            "CI run %s: workspace setup failed for step %d (exit %d): %s",
            run.id, step.stepIndex, result.exitCode(), firstLine(result.output()));
      }

      boolean ok = !result.timedOut() && result.exitCode() == 0;
      String output = result.output();
      if (result.timedOut()) {
        output = (output == null ? "" : output) + "\n[step timed out]";
      }
      updateStep(
          step.id,
          ok ? CiStepStatus.SUCCESS : CiStepStatus.FAILED,
          result.exitCode(),
          tail(output, outputMaxChars));
      failed |= !ok;
    }
    finishRun(run.id, failed ? CiRunStatus.FAILED : CiRunStatus.SUCCESS);
  }

  private CiRun persistRun(
      String repoId,
      String branch,
      String sha,
      CiRunStatus status,
      List<CiPipeline.CiStepDecl> declaredSteps) {
    CiRun run = new CiRun();
    run.id = UUID.randomUUID().toString();
    run.repoId = repoId;
    run.branch = branch;
    run.commitSha = sha;
    run.status = status;
    run.createdAt = Instant.now();
    if (status != CiRunStatus.RUNNING) {
      run.finishedAt = run.createdAt;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              runs.persist(run);
              for (int i = 0; i < declaredSteps.size(); i++) {
                CiStep step = new CiStep();
                step.id = UUID.randomUUID().toString();
                step.runId = run.id;
                step.stepIndex = i;
                step.image = declaredSteps.get(i).image();
                step.status = CiStepStatus.PENDING;
                steps.persist(step);
              }
            });
    return run;
  }

  private void updateStep(String stepId, CiStepStatus status, Integer exitCode, String output) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiStep step = steps.findById(stepId);
              step.status = status;
              step.exitCode = exitCode;
              step.output = output;
            });
  }

  /** Moves a run's non-terminal steps to terminal states (RUNNING ⇒ FAILED, PENDING ⇒ SKIPPED). */
  private void failIncompleteSteps(String runId) {
    for (CiStep step : steps.listByRunIdOrdered(runId)) {
      if (step.status == CiStepStatus.RUNNING) {
        step.status = CiStepStatus.FAILED;
      } else if (step.status == CiStepStatus.PENDING) {
        step.status = CiStepStatus.SKIPPED;
      }
    }
  }

  private void finishRun(String runId, CiRunStatus status) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiRun run = runs.findById(runId);
              run.status = status;
              run.finishedAt = Instant.now();
            });
  }

  /** Removes a run that turned out to describe a commit that no longer exists. */
  private void discardRun(String runId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.delete("runId = ?1", runId);
              runs.deleteById(runId);
            });
  }

  /** All runs recorded for a repository, newest-first. */
  public List<CiRun> runsFor(String repoId) {
    return runs.listByRepoIdNewestFirst(repoId);
  }

  /** The run, or 404. */
  public CiRun requireRun(String runId) {
    return runs.findByIdOptional(runId)
        .orElseThrow(() -> new NotFoundException("No such CI run: " + runId));
  }

  /** A run's steps in declaration order. */
  public List<CiStep> stepsFor(String runId) {
    return steps.listByRunIdOrdered(runId);
  }

  /** Keeps the LAST {@code maxChars} chars (a step's tail is where the failure is), marked. */
  static String tail(String output, int maxChars) {
    if (output == null || output.length() <= maxChars) {
      return output;
    }
    return TRUNCATION_MARKER + output.substring(output.length() - maxChars);
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no output)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /** Test hook: waits for the work queued at this moment to drain. */
  void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
