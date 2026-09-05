package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * The {@code steps:} half of a parsed {@code .config/qits/ci-event-*.yml}: the ordered list of steps
 * to run sequentially against the run's commit. The MVP schema is exactly this — later format
 * extensions (names, needs, caching, …) stay additive over the {@code steps} core, which is the path
 * {@code timeout-seconds} took.
 *
 * <p>It is a type of its own, rather than a field on {@link CiEventTrigger}, because it used to be
 * what <em>two</em> file kinds shared: {@code ci-post-receive.yml} was nothing but a pipeline, and a
 * step had to mean exactly the same thing in both. That file retired with per-push CI on 2026-09-05
 * and the split is kept — a pipeline and the declaration that triggers one are still two things.
 */
public record CiPipeline(List<CiStepDecl> steps) {

  /**
   * One step: the container {@code image} it runs in, the bash {@code script} it executes, an
   * optional {@code timeout-seconds}, whether it asked for a docker daemon, and the branches it is
   * bound to.
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
   *
   * <p>{@code user} is who the container's first process runs as. <b>Empty means the config declared
   * none</b>, which is the image's own default and is what every step has had until now. It exists
   * because a step cannot change user from the inside: a step container is started
   * {@code --cap-drop=ALL}, so it has no CAP_SETUID and no CAP_SETGID and {@code su} fails whatever
   * the script says — and no CAP_CHOWN either, so even root cannot {@code chown} the checkout.
   * Measured 2026-08-12, on qits-containers' post-receive step. The image has to carry a passwd
   * entry for the name: zonky's {@code initdb}, the reason a suite wants a non-root user at all,
   * refuses uid 0 and calls {@code getpwuid}.
   *
   * <p><b>{@code user} with {@code docker: true} is a parse error</b> — a step holding the host's
   * socket stays root. The socket's group is the host's fact, not this repository's, so a non-root
   * step could not use it anyway; refusing the pair is what keeps that from being discovered as a
   * permission denied halfway through a publish.
   *
   * <p><b>{@code gating} is whether THIS step's failure is a verdict about the commit</b>, and it is
   * what lets one file carry a gating half and a non-gating half. Absent means true, which is every
   * pipeline written before the key existed, byte for byte. A step declaring {@code gating: false}
   * still fails the run — the row is red and a person sees it — but the build event the run
   * announces carries {@code gating: false}, so a release gate reading per-commit verdicts does not
   * hold the commit for it.
   *
   * <p>The reason it is per step rather than per file is the sentence the old two-file split was
   * built on: <em>a red verify must not cost the image</em>. Two files bought that by never letting
   * the two halves share a verdict; one file buys it by <b>ordering plus classification</b> — the
   * gating half runs first and has already published whatever it publishes by the time a non-gating
   * step can fail, and the failure it produces is classified as the non-gating one. Put the
   * non-gating steps last; a non-gating step that fails still stops the run, exactly as any failing
   * step always has.
   */
  public record CiStepDecl(
      String image,
      String script,
      Integer timeoutSeconds,
      boolean docker,
      String user,
      boolean gating) {}

  // A step could also declare `branches:` — a list of matchers over the run's branch, entries OR'd,
  // absent meaning "every branch" — and it is GONE with per-push CI (2026-09-05). It was a pipeline
  // key rather than a trigger key, and it was already a parse error in a trigger file for a reason
  // that outlived the other file: an event-triggered run's branch is decided by the trigger, once,
  // before any step exists, so a per-step filter over it is either inert decoration or a step that
  // can never run — allow-but-inert, the trap the whole schema refuses. With `ci-post-receive.yml`
  // retired there is no file left that could declare one, so the record component, the BranchFilter
  // type and the SKIPPED-by-branch arm in CiRunService.runSteps went with it. The parse error stays
  // (CiConfigSchema.steps), because a repository that still carries the key must be told rather
  // than have it silently ignored.
}
