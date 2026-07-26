package eu.wohlben.qits.ci.control;

/**
 * Executes one pipeline step. The real implementation ({@link CiDockerRunner}) runs it in a fresh
 * container of the step's declared image; tests replace it with a host-process fake ({@code
 * @io.quarkus.test.Mock}) so the suites stay docker-free — the {@code ContainerRuntime}/{@code
 * FakeContainerRuntime} seam pattern, owned by ci so the runner leaves with the module.
 */
public interface CiStepRunner {

  /** Everything a step execution needs — repo/commit by string id only, never entities. */
  record StepSpec(
      String runId, int stepIndex, String repoId, String sha, String image, String script) {}

  /**
   * Combined (bounded) output plus exit code.
   *
   * <p>{@code timedOut} marks a step killed by the per-step timeout ({@code exitCode} is -1 then).
   * {@code workspaceReady} is false when the clone/checkout prelude failed, i.e. the script never
   * ran: the exit code then belongs to {@code git}, not to the pipeline, so the run must not be
   * recorded as a red verification (see {@code CiRunService}).
   */
  record StepResult(int exitCode, String output, boolean timedOut, boolean workspaceReady) {}

  StepResult run(StepSpec spec);
}
