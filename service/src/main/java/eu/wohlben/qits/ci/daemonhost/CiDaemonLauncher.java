package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiProcess;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Starts one step container and takes it away again: the sandbox flags, the entrypoint overridden to
 * a fixed host-authored bootstrap, and the run's whole context as environment.
 *
 * <p><b>qits-ci never executes anything.</b> Its docker vocabulary is container lifecycle — {@code
 * run}, {@code logs}, {@code rm}, {@code network inspect}/{@code create}, {@code ps} — and {@code
 * exec} is not in it, not even to deliver the daemon binary. The step's script never appears in an
 * argv assembled here: it reaches the container as the reply on the socket that container's own
 * daemon dialled, and executes as that daemon's child inside the sandbox. {@link #BOOTSTRAP} is a
 * compile-time constant with <b>zero interpolation</b> — it names its inputs as shell variables the
 * container reads out of its own environment, so no repository content is ever spliced into a
 * command line.
 *
 * <p><b>Containers run detached and are removed explicitly.</b> {@code --rm} is gone with the
 * attached {@code docker run}: the host no longer reads a pipe, it reads a socket, and a
 * self-removing container races the {@code docker logs} capture that is the only diagnosis a
 * container which never registered can offer. So every teardown path is a {@code docker rm -f}, and
 * the {@code qits.ci.run} label plus the boot sweep below is what catches the ones a crash left
 * behind.
 *
 * <p>This is the whole of qits-ci's container vocabulary. {@link CiDaemonStepRunner} is its only
 * caller in production; {@code CiDaemonGateIT} drives it against a real image.
 */
@ApplicationScoped
public class CiDaemonLauncher {

  private static final Logger LOG = Logger.getLogger(CiDaemonLauncher.class);

  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);

  /** Lines of container log kept as the bootstrap's error report. */
  private static final String LOG_TAIL_LINES = "200";

  /** Where the label-filtered boot sweep and the per-step label agree. */
  static final String RUN_LABEL = "qits.ci.run";

  /**
   * The container's entrypoint: fetch the daemon, make it executable, become it. A {@code static
   * final String}, host-authored, with nothing interpolated into it ever — the four values it needs
   * arrive as environment variables the shell expands inside the container, so a repository cannot
   * reach this text no matter what it declares.
   *
   * <p>Written for {@code /bin/sh} rather than bash, because the image contract is the repository's
   * choice and {@code sh} is the only shell an arbitrary image reliably has. It probes {@code wget}
   * then {@code curl} — one or the other is the downloader half of the contract — and says so
   * explicitly when neither is present, because <b>this text's stdout is the whole diagnosis of a
   * container that never registers</b>. That is why each failure arm names the url it could not
   * fetch instead of letting a bare non-zero exit stand: by the time the host notices, the only
   * thing it can ask is {@code docker logs}.
   *
   * <p>{@code exec} rather than a plain call, so the daemon is PID 1 and a {@code docker rm -f}
   * signals the process that owns the step rather than a shell wrapping it.
   */
  static final String BOOTSTRAP =
      """
      set -e
      if command -v wget >/dev/null 2>&1; then
        wget -q -O /tmp/qits-ci-daemon "$QITS_CI_DAEMON_BINARY_URL" \\
          || { echo "qits-ci: wget could not fetch $QITS_CI_DAEMON_BINARY_URL" >&2; exit 1; }
      elif command -v curl >/dev/null 2>&1; then
        curl -fsS -o /tmp/qits-ci-daemon "$QITS_CI_DAEMON_BINARY_URL" \\
          || { echo "qits-ci: curl could not fetch $QITS_CI_DAEMON_BINARY_URL" >&2; exit 1; }
      else
        echo "qits-ci: this image has neither wget nor curl, so the ci daemon cannot be fetched" >&2
        exit 127
      fi
      chmod +x /tmp/qits-ci-daemon
      exec /tmp/qits-ci-daemon
      """;

  @ConfigProperty(name = "qits.ci.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.ci.network")
  String network;

  @ConfigProperty(name = "qits.ci.container-git-url")
  String containerGitUrl;

  @ConfigProperty(name = "qits.ci.container-daemon-url")
  String containerDaemonUrl;

  /**
   * {@code Optional} for the same reason {@code qits.ci.token} is: the shipped default is blank, and
   * SmallRye reads a blank value as unset rather than as an empty string. Blank means this
   * deployment has not pinned a daemon binary, which yields a url that 404s and the honest
   * never-registered failure state — not a boot failure, and not a default this class invents.
   */
  @ConfigProperty(name = "qits.ci.daemon-version")
  Optional<String> daemonVersion;

  @ConfigProperty(name = "qits.ci.daemon-binary-url-template")
  String daemonBinaryUrlTemplate;

  @ConfigProperty(name = "qits.ci.daemon-register-timeout-seconds")
  long registerTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  @ConfigProperty(name = "qits.ci.memory-limit")
  String memoryLimit;

  @ConfigProperty(name = "qits.ci.pids-limit")
  String pidsLimit;

  @ConfigProperty(name = "qits.ci.cpus")
  String cpus;

  /** Everything one step container is started with. Ids and names only — never entities. */
  public record LaunchSpec(
      String runId,
      int stepIndex,
      String repoId,
      String branch,
      String sha,
      String image,
      String daemonId,
      String secret,
      String daemonBinaryUrl) {}

  /**
   * Whether the container started, under what name, and what docker said if it did not. A failed
   * launch is its own recorded outcome — "docker refused" is not "the step failed".
   */
  public record Launched(boolean started, String containerName, String error) {}

  /**
   * The boot half of the fail-and-reap reconciliation. {@code CiRunService.onStart} already fails
   * runs a crash left {@code RUNNING}; this reaps the containers those runs left behind, found by
   * the label every step container carries. The registry starts empty, so a daemon from a previous
   * life that manages to dial in presents a secret this process does not know and is closed 1008 —
   * its container is already gone or about to be.
   *
   * <p>It is a second observer rather than an edit to {@code CiRunService.onStart} because that
   * method lives in the {@code ci} module, which has no web stack and must not gain a dependency on
   * this one. The two halves run at the same event and mean one thing together: no run claims to be
   * executing, and nothing it started is still running.
   *
   * <p>Skipped under {@code TEST}, like the runner's own startup observer: the suites are docker-free
   * by intent and a test app must not reach the host's docker daemon to prove it.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    ensureNetwork();
    int reaped = reapOrphans();
    if (reaped > 0) {
      LOG.infof("Removed %d orphaned CI step container(s) left by a previous shutdown", reaped);
    }
  }

  /**
   * Best-effort ensure the step network exists — inspect, then create, warning rather than failing
   * when docker is absent. Called from the boot observer below, and directly by the gate IT, which
   * has no Quarkus lifecycle to fire it.
   */
  public void ensureNetwork() {
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

  /**
   * The run-pinned download url for the daemon binary. The version and the url move together — one
   * template with a {@code {version}} placeholder rather than two free values that can disagree —
   * and the version is resolved once per run so a deploy landing mid-run cannot make step 3 speak a
   * different protocol than step 1.
   */
  public String resolveBinaryUrl(String version) {
    return daemonBinaryUrlTemplate.replace("{version}", version == null ? "" : version);
  }

  /** The daemon version this process is configured to pin onto a new run; blank when unset. */
  public String daemonVersion() {
    return daemonVersion.orElse("");
  }

  /** How long a launch may take, which is mostly how long an image pull may take. */
  public Duration launchTimeout() {
    return Duration.ofSeconds(registerTimeoutSeconds);
  }

  /** Start the step container, detached. The daemon dials back; nothing dials in. */
  public Launched launch(LaunchSpec spec) {
    CiIdentifiers.requireRepoId(spec.repoId());
    CiIdentifiers.requireBranch(spec.branch());
    CiIdentifiers.requireSha(spec.sha());

    String name = containerName(spec.runId(), spec.stepIndex());
    CiProcess.Result result =
        CiProcess.run(null, buildArgv(spec), launchTimeout(), outputMaxChars);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf("Could not start step container %s: %s", name, result.output());
      return new Launched(false, name, result.output());
    }
    LOG.debugf("Started step container %s for run %s step %d", name, spec.runId(), spec.stepIndex());
    return new Launched(true, name, null);
  }

  /**
   * A bounded tail of the container's own output. This is the bootstrap's error report and the only
   * thing a container that never registered has to say, so it is captured <b>before</b> the reap —
   * which is the other reason {@code --rm} is gone.
   */
  public String logs(String containerName) {
    CiProcess.Result result =
        CiProcess.run(
            null,
            List.of(runtime, "logs", "--tail", LOG_TAIL_LINES, containerName),
            CLEANUP_TIMEOUT,
            outputMaxChars);
    return result.output() == null ? "" : result.output();
  }

  /** Remove the container, running or not. Every teardown path ends here. */
  public void reap(String containerName) {
    CiProcess.Result result =
        CiProcess.run(null, List.of(runtime, "rm", "-f", containerName), CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove step container %s: %s", containerName, result.output());
    }
  }

  /** Remove every container carrying the ci run label. Returns how many there were. */
  public int reapOrphans() {
    CiProcess.Result listed =
        CiProcess.run(
            null,
            List.of(runtime, "ps", "-aq", "--filter", "label=" + RUN_LABEL),
            CLEANUP_TIMEOUT,
            8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list orphaned CI step containers: %s", listed.output());
      return 0;
    }
    List<String> ids =
        Arrays.stream((listed.output() == null ? "" : listed.output()).split("\\R"))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .toList();
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> argv = new ArrayList<>(List.of(runtime, "rm", "-f"));
    argv.addAll(ids);
    CiProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    return ids.size();
  }

  /** Package-private for argv assembly tests. */
  List<String> buildArgv(LaunchSpec spec) {
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    // Detached: the host reads a socket, not this process's pipe. Note the absence of --rm.
    argv.add("-d");
    argv.add("--name");
    argv.add(containerName(spec.runId(), spec.stepIndex()));
    argv.add("--network");
    argv.add(network);
    argv.add("--add-host=host.docker.internal:host-gateway");
    argv.add("--label");
    argv.add(RUN_LABEL + "=" + spec.runId());
    // The step's script is repo-controlled: drop privileges and bound the blast radius. The daemon
    // runs inside this sandbox and the script is its child, so these caps bound both.
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
    // The contract, as environment. The daemon needs all of it before a socket exists, which is why
    // none of it is a message.
    env(argv, "QITS_CI_DAEMON_ID", spec.daemonId());
    env(argv, "QITS_CI_DAEMON_SECRET", spec.secret());
    env(argv, "QITS_CI_DAEMON_URL", containerDaemonUrl);
    env(argv, "QITS_CI_DAEMON_BINARY_URL", spec.daemonBinaryUrl());
    env(argv, "QITS_CI_REPOSITORY_URL", cloneUrl(spec.repoId()));
    env(argv, "QITS_CI_BRANCH", spec.branch());
    env(argv, "QITS_CI_SHA", spec.sha());
    env(argv, "QITS_CI_REPO_ID", spec.repoId());
    // For the step script rather than the daemon: the de-facto convention tooling checks for
    // non-interactive mode, and one that says which CI this is.
    env(argv, "CI", "true");
    env(argv, "QITS_CI", "true");
    argv.add("--entrypoint");
    argv.add("/bin/sh");
    argv.add(spec.image());
    argv.add("-c");
    argv.add(BOOTSTRAP);
    return List.copyOf(argv);
  }

  private static void env(List<String> argv, String key, String value) {
    argv.add("--env");
    argv.add(key + "=" + (value == null ? "" : value));
  }

  /**
   * The id-addressed smart-HTTP url of a repository, as reachable from inside a step container.
   * {@code /git} is the codebase's second-level segment for the git wire protocol, so it lives here;
   * the configured base names only which service hosts it. It is the daemon's {@code
   * $QITS_CI_REPOSITORY_URL} — a value the container clones from, never a word in a command line.
   */
  String cloneUrl(String repoId) {
    return containerGitUrl.replaceAll("/+$", "") + "/git/" + repoId;
  }

  /** One name shape and one label convention, shared by the launch, the reap and the boot sweep. */
  static String containerName(String runId, int stepIndex) {
    String shortRun = runId.length() > 8 ? runId.substring(0, 8) : runId;
    return "qits-ci-" + shortRun + "-" + stepIndex;
  }
}
