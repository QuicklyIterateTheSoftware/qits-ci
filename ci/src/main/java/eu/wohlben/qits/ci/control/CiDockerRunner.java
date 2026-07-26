package eu.wohlben.qits.ci.control;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs a step as one fresh container: {@code docker run --rm} of the step's declared image on the
 * configured network, cloning the repository at the pushed sha into {@code /workspace} from the git
 * host, then {@code bash -c <script>} with CWD {@code /workspace}. ci shells the docker CLI through
 * its <b>own</b> thin executor (mirroring {@code DockerExecutor}'s approach, deliberately not
 * reusing it) so the runner survives extraction. Step images must therefore contain {@code git} and
 * {@code bash}; an image without them fails its step with an honest prelude error in the output.
 *
 * <p><b>The script is repo-controlled code.</b> That is the feature — but it means the container is
 * a hostile-code sandbox, so it runs with {@code --cap-drop=ALL}, {@code no-new-privileges}, and
 * explicit memory/pids/cpu caps, and never sees the docker socket. The clone url and sha are passed
 * as {@code bash} <b>positional arguments</b> ({@code $1}/{@code $2}), never interpolated into the
 * script text, so a hostile sha cannot inject commands. The residual exposure (the network the step
 * shares, and the fact that an unauthenticated push can trigger a run at all) is tracked in
 * docs/issues/2026-07-26_ci-executes-repo-controlled-code-from-unauthenticated-pushes.md.
 *
 * <p>No state crosses steps — each gets a fresh clone (caching is a follow-up). A step exceeding
 * the timeout is force-removed ({@code docker rm -f}, which also stops the container the killed
 * {@code docker run} client left behind).
 */
@ApplicationScoped
public class CiDockerRunner implements CiStepRunner {

  private static final Logger LOG = Logger.getLogger(CiDockerRunner.class);

  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Printed by an {@code ERR} trap when the clone/checkout prelude fails, i.e. the script never
   * ran.
   *
   * <p>Deliberately a <b>failure</b> sentinel rather than a success one: step output is bounded by
   * a rolling tail, so a marker printed before a chatty script would be trimmed away and a green
   * step would be misread as a broken workspace. The prelude's own output is tiny, so a sentinel
   * emitted at the point of failure is always in the tail. The trap is cleared before the user's
   * script, so the script's own failures never raise it.
   */
  static final String PRELUDE_FAILED_MARKER = "__qits_ci_prelude_failed__";

  @ConfigProperty(name = "qits.ci.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.ci.network")
  String network;

  @ConfigProperty(name = "qits.ci.container-git-url")
  String containerGitUrl;

  @ConfigProperty(name = "qits.ci.step-timeout-seconds")
  long stepTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  @ConfigProperty(name = "qits.ci.memory-limit")
  String memoryLimit;

  @ConfigProperty(name = "qits.ci.pids-limit")
  String pidsLimit;

  @ConfigProperty(name = "qits.ci.cpus")
  String cpus;

  /**
   * Best-effort ensure the step network exists (the {@code DockerExecutor.ensureNetwork} mirror —
   * inspect-then-create, a warning when docker is absent, never a startup failure).
   *
   * <p>Skipped under {@code TEST}: {@code @Mock}ing {@link CiStepRunner} only replaces the bean at
   * injection points, so this observer would still fire in every test app and mutate the host
   * docker daemon — the suites are docker-free by intent (the {@code ArtifactsStartupSeed}
   * launch-mode stance).
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    ensureNetwork();
  }

  void ensureNetwork() {
    if (CiProcess.run(null, List.of(runtime, "network", "inspect", network), CLEANUP_TIMEOUT, 8192)
            .exitCode()
        == 0) {
      return;
    }
    CiProcess.Result create =
        CiProcess.run(null, List.of(runtime, "network", "create", network), CLEANUP_TIMEOUT, 8192);
    if (create.exitCode() != 0) {
      LOG.warnf("Could not ensure ci network '%s': %s", network, create.output());
    }
  }

  @Override
  public StepResult run(StepSpec spec) {
    CiIdentifiers.requireRepoId(spec.repoId());
    CiIdentifiers.requireSha(spec.sha());

    String name = containerName(spec);
    CiProcess.Result result =
        CiProcess.run(
            null,
            buildArgv(spec),
            Duration.ofSeconds(stepTimeoutSeconds),
            // Leave room for the sentinel line on top of the caller's output budget.
            outputMaxChars + PRELUDE_FAILED_MARKER.length() + 1);
    if (result.timedOut()) {
      // Killing the attached `docker run` client does not stop the container — remove it.
      CiProcess.run(null, List.of(runtime, "rm", "-f", name), CLEANUP_TIMEOUT, 8192);
    }
    String output = result.output() == null ? "" : result.output();
    boolean ready = !output.contains(PRELUDE_FAILED_MARKER);
    return new StepResult(result.exitCode(), stripMarker(output), result.timedOut(), ready);
  }

  /** Package-private for argv assembly tests. */
  List<String> buildArgv(StepSpec spec) {
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.add("--rm");
    argv.add("--name");
    argv.add(containerName(spec));
    argv.add("--network");
    argv.add(network);
    argv.add("--add-host=host.docker.internal:host-gateway");
    argv.add("--label");
    argv.add("qits.ci.run=" + spec.runId());
    // The script is repo-controlled: drop privileges and bound the blast radius.
    argv.add("--security-opt=no-new-privileges");
    argv.add("--cap-drop=ALL");
    argv.add("--memory");
    argv.add(memoryLimit);
    argv.add("--memory-swap");
    argv.add(memoryLimit);
    argv.add("--pids-limit");
    argv.add(pidsLimit);
    argv.add("--cpus");
    argv.add(cpus);
    argv.add(spec.image());
    argv.add("bash");
    argv.add("-c");
    argv.add(composite(spec));
    // $0, then $1 = clone url, $2 = sha — passed as ARGUMENTS, never interpolated.
    argv.add("qits-ci");
    argv.add(cloneUrl(spec.repoId()));
    argv.add(spec.sha());
    return List.copyOf(argv);
  }

  /**
   * The in-container script: a strict clone/checkout prelude over the positional arguments, guarded
   * by an {@code ERR} trap that marks a prelude failure distinguishably, then the user's script
   * verbatim — its own exit code is the step's.
   */
  String composite(StepSpec spec) {
    return "set -e\n"
        + ("trap 'echo " + PRELUDE_FAILED_MARKER + "; exit 125' ERR\n")
        + "git clone -q \"$1\" /workspace\n"
        + "cd /workspace\n"
        + "git checkout -q \"$2\"\n"
        + "trap - ERR\n"
        + "set +e\n"
        + spec.script();
  }

  String cloneUrl(String repoId) {
    return containerGitUrl.replaceAll("/+$", "") + "/git/" + repoId;
  }

  /** Drops the sentinel line so it never shows up in a user-visible step log. */
  private static String stripMarker(String output) {
    if (output == null || !output.contains(PRELUDE_FAILED_MARKER)) {
      return output;
    }
    return output.replace(PRELUDE_FAILED_MARKER + "\n", "").replace(PRELUDE_FAILED_MARKER, "");
  }

  static String containerName(StepSpec spec) {
    String shortRun = spec.runId().length() > 8 ? spec.runId().substring(0, 8) : spec.runId();
    return "qits-ci-" + shortRun + "-" + spec.stepIndex();
  }
}
