package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * The parsed {@code .config/qits/ci-post-receive.yml}: the ordered list of steps to run
 * sequentially against the pushed commit. The MVP schema is exactly this — later format extensions
 * (names, needs, caching, …) stay additive over the {@code steps} core.
 */
public record CiPipeline(List<CiStepDecl> steps) {

  /** One step: the container {@code image} it runs in and the bash {@code script} it executes. */
  public record CiStepDecl(String image, String script) {}
}
