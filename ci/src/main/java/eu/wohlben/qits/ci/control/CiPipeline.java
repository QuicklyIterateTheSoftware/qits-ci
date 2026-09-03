package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
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
   * <p>{@code branches} is the step's own {@code branches:} filter, {@link #runsOnBranch evaluated}
   * before the container launches. <b>Empty means the config declared none</b>, and the empty list
   * has exactly one origin: {@code branches: []} in a file is a parse error, because both readings
   * of it already have an unambiguous spelling (omit the key; delete the step).
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
      boolean gating,
      List<BranchFilter> branches) {

    /**
     * Whether this step's declaration binds it to the branch a run is on. An undeclared filter binds
     * every branch — today's behaviour, byte for byte — and a declared one binds when <b>any</b>
     * entry matches.
     */
    public boolean runsOnBranch(String branch) {
      if (branches == null || branches.isEmpty()) {
        return true;
      }
      for (BranchFilter filter : branches) {
        if (filter.matches(branch)) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * One entry of a step's {@code branches:}: every matcher in it must hold. Entries are OR'd and a
   * mapping's keys are AND'd — the {@code when:} DSL's composition rule minus the path level,
   * because the subject is one scalar rather than a payload.
   *
   * <p>It carries {@link Matcher} rather than a second matcher type: one matcher implementation on
   * this platform, not two. The vocabulary a branch filter may spell is narrower, and that is the
   * parser's rule rather than this record's — {@code exists} over a value that is always present
   * could only ever say yes.
   */
  public record BranchFilter(List<Matcher> matchers) {

    public boolean matches(String branch) {
      for (Matcher matcher : matchers) {
        if (!CiEventSelectionEvaluator.matchesScalar(matcher, branch)) {
          return false;
        }
      }
      return true;
    }
  }
}
