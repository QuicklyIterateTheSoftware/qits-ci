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
   * One step: the container {@code image} it runs in, the bash {@code script} it executes, an
   * optional {@code timeout-seconds}, and whether it asked for a docker daemon.
   *
   * <p>{@code timeoutSeconds} is null when the config does not declare one, which means exactly
   * today's behaviour — the deployment-wide {@code qits.ci.step-timeout-seconds}. It is resolved by
   * {@code CiRunService}, not defaulted here, so the declaration keeps saying "the config said
   * nothing" rather than baking one deployment's number into a parsed document.
   *
   * <p>{@code docker} is a plain {@code boolean} rather than a {@code Boolean}, because unlike a
   * timeout it has no deployment-wide default to fall back to: absent means false and false means
   * the sandbox this repository has always described. It makes the host mount its own docker socket
   * into that step's container, which is how a pipeline publishes an image (a final step whose
   * script is {@code docker build && docker push}) and is also <b>root-equivalent on the host</b> —
   * the socket is the daemon and the daemon is root. That is why the flag is declared in the
   * repository's own config: it shows up in a config diff, and no step acquires it silently.
   */
  public record CiStepDecl(String image, String script, Integer timeoutSeconds, boolean docker) {}
}
