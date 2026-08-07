package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiProcess;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

  /**
   * How much of docker's own complaint reaches the WARN when the boot sweep cannot list containers.
   * The tail rather than the head, because the reason a CLI failed is the last thing it says.
   */
  private static final int DOCKER_ERROR_TAIL_CHARS = 500;

  /**
   * Boot order, first half. This observer runs <b>before</b> {@code CiRunService.onStart}, which
   * carries the matching {@code @Priority} one step higher. <b>Move neither alone</b> — see {@link
   * #onStart} for what the order buys.
   *
   * <p>Public only so that {@code BootReconciliationOrderTest}, which sits in the other half's
   * package, can state both numbers in one place instead of restating either as a literal.
   */
  public static final int BOOT_REAP_PRIORITY = 2000;

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
   * The daemon pin ladder (ci-daemon-autoadopt-plan.md, workstream BV): the top adopted candidate
   * that has proven itself, or the deployment's configured {@code qits.ci.daemon-version} pin when
   * none has, or blank. {@link #daemonVersion()} delegates to it entirely — this class no longer
   * reads {@code qits.ci.daemon-version} itself.
   */
  @Inject CiDaemonPins pins;

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

  /**
   * The host socket a {@code docker: true} step is handed, mounted at the same path inside the
   * container so the CLI in the step image finds it where it looks by default. Configurable because
   * a nonstandard daemon (a rootless one under {@code $XDG_RUNTIME_DIR}, a socket-activated proxy)
   * is a deployment fact and not something this class should assume.
   */
  @ConfigProperty(name = "qits.ci.docker-socket-path")
  String dockerSocketPath;

  /**
   * qits-artifacts' registry coordinates, injected into every step container so a publish script
   * names no deployment fact of its own. Receiver-named on purpose: they are the artifacts service's
   * address and image namespace, one spelling shared with qits-cd, which derives its pull references
   * from the same two values. Neither is dialled by <em>this</em> process — see {@link #buildArgv}.
   */
  @ConfigProperty(name = "qits.artifacts.registry-host")
  String artifactsRegistryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String artifactsImageRepository;

  /**
   * qits-artifacts' npm registry roots — the hosted repository {@code @qits/*} is published to, and
   * the pull-through cache of npmjs every install resolves through. Same receiver-naming rule as the
   * two above, and injected into every step container for the same reason: a repository's pipeline
   * writes its {@code ~/.npmrc} from these and spells no address of its own.
   *
   * <p><b>Who dials them is the opposite of {@code registry-host}'s answer</b> — the step container
   * itself, over qits-net, with no docker socket and no host daemon in the path. See {@link
   * #buildArgv}.
   */
  @ConfigProperty(name = "qits.artifacts.npm.hosted-url")
  String artifactsNpmHostedUrl;

  @ConfigProperty(name = "qits.artifacts.npm.proxy-url")
  String artifactsNpmProxyUrl;

  /**
   * qits-artifacts' hosted Maven repository root. Dialled by the step container over qits-net, like
   * the npm roots above, and injected so Maven release pipelines and dependency bump handlers never
   * hard-code a deployment address.
   */
  @ConfigProperty(name = "qits.artifacts.maven.registry-url")
  String artifactsMavenRegistryUrl;

  /**
   * qits-artifacts' docs repository root, including the {@code docs} namespace segment. Dialled by
   * the step container over qits-net like the npm and maven roots, and injected so a release
   * pipeline publishing its documentation names no deployment address.
   *
   * <p>The namespace is part of the value rather than the step's to choose: there is one docs
   * repository, seeded on first boot, and a pipeline that got to name one could publish into a
   * namespace nothing serves.
   */
  @ConfigProperty(name = "qits.artifacts.docs.url")
  String artifactsDocsUrl;

  /**
   * qits-workspaces' root, injected into every step container so the release train's maintenance
   * step names no deployment fact of its own. Scheme, host and port only — the path is the caller's,
   * and a step spells {@code /workspaces/api/branches/release} itself.
   *
   * <p>Dialled by the step container, like the npm pair and unlike {@code registry-host}: an
   * ordinary HTTP call over qits-net, no socket and no host daemon in the path.
   */
  @ConfigProperty(name = "qits.ci.workspaces-url")
  String workspacesUrl;

  /**
   * Everything one step container is started with. Ids and names only — never entities.
   *
   * <p>{@code docker} is the step's own declaration, arriving from the repository's config by way of
   * the step seam. It is the single input that changes the sandbox, and it changes it in exactly one
   * way: one more bind mount. See {@link #buildArgv}.
   */
  public record LaunchSpec(
      String runId,
      int stepIndex,
      String repoId,
      String branch,
      String sha,
      String image,
      String daemonId,
      String secret,
      String daemonBinaryUrl,
      boolean docker,
      Map<String, String> env) {}

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
   * <p><b>This half runs first, and the order is now stated rather than left to the container.</b>
   * {@code CiRunService.sweepInterrupted} does not only write rows — it puts work back on the run
   * worker, restarting every interrupted event run and re-enqueueing every {@code QUEUED} one, and
   * that worker starts labelled containers as soon as it has work. A sweep-then-reap order would let
   * this sweep {@code docker rm -f} a container the restarted run had just launched, because the
   * filter is the label alone and a fresh container wears it exactly like a stale one. Reaping first
   * closes that window: by the time any run can start, the previous life's containers are gone.
   * {@code @Priority} on both observers is what encodes it — this one is {@link
   * #BOOT_REAP_PRIORITY}, {@code CiRunService.onStart} is the higher number, and <b>neither moves
   * alone</b>. {@code BootReconciliationOrderTest} holds it.
   *
   * <p>Skipped under {@code TEST}, like the runner's own startup observer: the suites are docker-free
   * by intent and a test app must not reach the host's docker daemon to prove it.
   */
  void onStart(@Observes @Priority(BOOT_REAP_PRIORITY) StartupEvent event) {
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

  /**
   * The daemon version a run started right now would pin — the top of {@link #pins}'s ladder;
   * blank when neither an adopted candidate nor the configured pin exists.
   *
   * <p><b>Delegates entirely, and that is the whole of the flip.</b> Before
   * ci-daemon-autoadopt-plan.md workstream BV this read {@code qits.ci.daemon-version} itself and a
   * boot-time check (long since deleted, {@code daemonVersionComplaint}) warned when the value could
   * not be a sha256 the old digest-addressed template needed. That check went silent by construction
   * the moment {@code qits.ci.daemon-binary-url-template} stopped saying {@code sha256:{version}} —
   * see {@code CiIdentifiers.requireDaemonVersion}, its replacement, enforced where a version now
   * actually arrives untrusted: at adoption, not at boot.
   */
  public String daemonVersion() {
    return pins.answer().version();
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
    // The image comes from the repository's own config rather than from the intake, but it lands in
    // the same argv as the rest, so it is checked in the same place and to the same standard.
    CiIdentifiers.requireImage(spec.image());

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

  /**
   * Remove every container carrying the ci run label. Returns how many there were.
   *
   * <p><b>This is host-wide, and it has to be.</b> The filter is the label and nothing else, so it
   * removes every labelled container on the docker daemon — including one another qits-ci is running
   * a step in right now. That is not an oversight to narrow: after a crash there is no record left of
   * which containers were this process's, which is the whole reason the sweep exists. So <b>one
   * qits-ci per docker daemon</b> is a deployment constraint, stated in {@code AGENTS.md} and in
   * {@code README.md}'s deployment section where an operator meets it.
   *
   * <p><b>A failed listing is a WARN, not a DEBUG.</b> The success path only logs a positive count,
   * so at DEBUG "the sweep could not run" and "there was nothing to sweep" left identical logs — and
   * the first of those means every orphan from the previous life is still on the host. It still
   * returns 0 rather than throwing: docker being briefly down must not stop this process from
   * booting.
   */
  public int reapOrphans() {
    CiProcess.Result listed =
        CiProcess.run(
            null,
            List.of(runtime, "ps", "-aq", "--filter", "label=" + RUN_LABEL),
            CLEANUP_TIMEOUT,
            8192);
    if (listed.exitCode() != 0) {
      LOG.warnf(
          "Could not sweep orphaned CI step containers: '%s ps' exited %d%s, so any container a"
              + " previous life left behind is still on this host. It said: %s",
          runtime,
          listed.exitCode(),
          listed.timedOut() ? " (timed out)" : "",
          dockerErrorTail(listed.output()));
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

  /**
   * The tail of what the docker CLI said, short enough for one log line. {@link CiProcess} merges
   * stderr into stdout, so this is the whole complaint; an empty one is named rather than logged as
   * a blank, because "docker printed nothing" is itself the diagnosis when the binary is missing.
   */
  private static String dockerErrorTail(String output) {
    String text = output == null ? "" : output.strip();
    if (text.isEmpty()) {
      return "(nothing)";
    }
    return text.length() <= DOCKER_ERROR_TAIL_CHARS
        ? text
        : "..." + text.substring(text.length() - DOCKER_ERROR_TAIL_CHARS);
  }

  /**
   * The whole {@code docker run} command line. Two paragraphs of it are load-bearing enough that a
   * flag lost in a refactor is a security regression, so {@code CiDaemonLauncherTest} asserts the
   * list literally — including the <b>absence</b> of the docker-socket mount for a step that did not
   * ask for one.
   *
   * <p><b>The socket mount is the one privilege a repository can ask for.</b> A step declaring
   * {@code docker: true} gets the host's docker socket bind-mounted at its own path, which is how
   * publishing works: the step's CLI streams its build context to the <em>host's</em> daemon, which
   * builds, tags and pushes. The sandbox flags below stay exactly as they are for such a step —
   * {@code --cap-drop=ALL} and {@code no-new-privileges} cost a socket <em>client</em> nothing, and
   * keeping them unconditional keeps them meaning what they mean for every step that does not opt in.
   * They also do not make the opt-in safe: a step holding this socket is <b>root-equivalent on the
   * host</b>, because those caps fence the step's own process tree and not what the daemon will do on
   * its behalf. That is accepted for the POC and it is per step, declared in the repository's config
   * where a diff shows it — see {@code AGENTS.md}'s untrusted-input section.
   *
   * <p><b>The registry coordinates are injected into every container, opted in or not.</b> They are
   * two strings a publish script would otherwise have to hard-code, and a script that hard-codes a
   * deployment's registry address is a script that breaks on the next deployment. Note what dials
   * that address: not this process, and not the step's CLI either, but the <b>host's docker
   * daemon</b>, on the far side of the mounted socket. So resolvability and TLS trust are the
   * daemon's — a deployment must make the host reach it, and list it in {@code insecure-registries}
   * while the registry speaks plain HTTP.
   *
   * <p><b>The package registry roots go into every container too, and their caveat is the exact
   * inverse.</b> {@code QITS_MAVEN_REGISTRY_URL} and {@code QITS_DOCS_URL} follow the same rule as
   * the npm pair below.
   * {@code QITS_NPM_REGISTRY_URL} and {@code QITS_NPM_PROXY_URL} are dialled by the <b>step
   * container itself</b> — an npm CLI speaking plain HTTP to a service alias on the shared network,
   * needing no socket, no privilege and no {@code docker: true}. So the value that is right here is
   * the in-network one, and a host-published mapping substituted for {@code QITS_REGISTRY} (the
   * local stack's {@code localhost:8081}) must <b>not</b> be substituted for these: a step container
   * has no such address. Two variables, two opposite readings of "reachable from where" — which is
   * why both are commented where they are shipped.
   *
   * <p><b>{@code QITS_WORKSPACES_URL} joins them on the same reading.</b> It is the door a step
   * knocks on to release its own repository after green tests — the release train's maintenance leg
   * — and it is an ordinary HTTP call from the container over qits-net.
   */
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
    // The declared opt-in, and the only thing that ever adds to this argv. Same path on both sides so
    // the step image's CLI finds it where it looks; nothing else about the step changes.
    if (spec.docker()) {
      argv.add("-v");
      argv.add(dockerSocketPath + ":" + dockerSocketPath);
    }
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
    // Also for the script: where a published image goes. Every container gets them, because "which
    // registry" must never be a literal in a repository's pipeline. Together with $QITS_CI_SHA above
    // they are the whole of the tag convention qits-cd pulls by,
    // <registry>/<repository>/<application>:<sha>.
    env(argv, "QITS_REGISTRY", artifactsRegistryHost);
    env(argv, "QITS_IMAGE_REPOSITORY", artifactsImageRepository);
    // And where npm packages come from and go to. Unlike the two above, these are dialled by this
    // container, on this network — a publish here is an ordinary HTTP step needing no socket.
    env(argv, "QITS_NPM_REGISTRY_URL", artifactsNpmHostedUrl);
    env(argv, "QITS_NPM_PROXY_URL", artifactsNpmProxyUrl);
    env(argv, "QITS_MAVEN_REGISTRY_URL", artifactsMavenRegistryUrl);
    env(argv, "QITS_DOCS_URL", artifactsDocsUrl);
    // And where a step asks for its own repository to be released — same network, same reading of
    // "reachable from where" as the npm pair.
    env(argv, "QITS_WORKSPACES_URL", workspacesUrl);
    // Run-scoped extras, LAST and in sorted key order. Last because everything above is the fixed
    // contract the daemon boots on and nothing may shadow it — today these are the four QITS_EVENT_*
    // of an event-triggered run and the map is empty on every push, but "the platform's variables are
    // written first" is the property worth keeping rather than the current contents. Sorted because
    // the whole argv is asserted literally by CiDaemonLauncherTest, and a set's iteration order is
    // not a thing to assert against.
    for (Map.Entry<String, String> extra : new TreeMap<>(spec.env()).entrySet()) {
      env(argv, extra.getKey(), extra.getValue());
    }
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

  /**
   * One name shape and one label convention, shared by the launch, the reap and the boot sweep.
   *
   * <p><b>The leading characters of {@code runId} are a human hint, never the whole name.</b> Two
   * different run ids that happen to share their first 8 characters must never collide on the
   * resulting container name -- which a blind 8-character prefix does not guarantee, and which is
   * exactly the incident this guards against: every probe run id used to start with the literal
   * {@code "daemon-probe-"} constant, so its first 8 characters were always {@code "daemon-p"} and
   * two concurrent probes always named the same container. A short disambiguator derived from the
   * <em>whole</em> {@code runId} rides alongside the hint instead, so a shared prefix is no longer
   * enough to collide -- see {@code CiDaemonLauncherTest} for the worked example, including the case
   * this incident actually hit.
   *
   * <p>{@code Integer.toHexString(runId.hashCode())} is deterministic: the same {@code runId} always
   * names the same container, which matters because {@link #reap} and the label-filtered boot sweep
   * both have to find what {@link #launch} started. Its output is hex digits only, already inside
   * docker's container-name charset ({@code [a-zA-Z0-9][a-zA-Z0-9_.-]*}), so nothing further needs
   * sanitizing.
   */
  static String containerName(String runId, int stepIndex) {
    String shortRun = runId.length() > 8 ? runId.substring(0, 8) : runId;
    String disambiguator = Integer.toHexString(runId.hashCode());
    return "qits-ci-" + shortRun + "-" + disambiguator + "-" + stepIndex;
  }
}
