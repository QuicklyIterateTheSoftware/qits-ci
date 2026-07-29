package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * The parsed {@code .config/qits/ci-post-receive.yml}: the ordered list of steps to run
 * sequentially against the pushed commit. The MVP schema is exactly this — later format extensions
 * (names, needs, caching, …) stay additive over the {@code steps} core, which is the path {@code
 * timeout-seconds} took.
 */
public record CiPipeline(List<CiStepDecl> steps) {

  /**
   * One step: the container {@code image} it runs in, the bash {@code script} it executes, and an
   * optional {@code timeout-seconds}.
   *
   * <p>{@code timeoutSeconds} is null when the config does not declare one, which means exactly
   * today's behaviour — the deployment-wide {@code qits.ci.step-timeout-seconds}. It is resolved by
   * {@code CiRunService}, not defaulted here, so the declaration keeps saying "the config said
   * nothing" rather than baking one deployment's number into a parsed document.
   */
  public record CiStepDecl(String image, String script, Integer timeoutSeconds) {}
}
