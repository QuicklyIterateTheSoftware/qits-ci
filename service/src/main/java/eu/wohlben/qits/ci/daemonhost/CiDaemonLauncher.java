package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.containers.client.ContainersAnswer;
import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.ContainersWire.DeleteOutcome;
import eu.wohlben.qits.containers.client.ContainersWire.Destroyed;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Observed;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Security;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Asks qits-containers to put one step container at a place, and to take it away again: the sandbox
 * flags, the entrypoint overridden to a fixed host-authored bootstrap, and the run's whole context
 * as environment.
 *
 * <p><b>This process holds no docker socket and spawns no process at all.</b> Every line of docker
 * vocabulary that used to live here — {@code run}, {@code logs}, {@code rm}, {@code ps}, {@code
 * network inspect}/{@code create} — is now one HTTP call to the orchestrator, which owns the daemon.
 * What survived is the seam: the same five public methods, the same {@link LaunchSpec}, and the
 * same {@link Launched}, so {@link CiDaemonStepRunner} reads exactly as it did.
 *
 * <p><b>qits-ci still executes nothing.</b> The step's script never appears in a spec assembled
 * here: it reaches the container as the reply on the socket that container's own daemon dialled, and
 * executes as that daemon's child inside the sandbox. {@link #BOOTSTRAP} is a compile-time constant
 * with <b>zero interpolation</b>, and it now travels as JSON rather than as an argv — {@code
 * entrypoint} is {@code ["/bin/sh"]} and {@code args} is {@code ["-c", BOOTSTRAP]}, two list
 * elements the orchestrator hands {@code ProcessBuilder} one at a time. Zero interpolation is
 * therefore preserved <em>by construction</em>: there is no string this text is concatenated into on
 * either side of the wire.
 *
 * <p><b>Containers are removed explicitly and never self-remove.</b> A self-removing container races
 * the log capture that is the only diagnosis a container which never registered can offer, so every
 * teardown path is a delete — and the one that needs the log asks for it in the <em>same</em> call
 * ({@link #destroyWithLogs}), which is what makes the ordering impossible to lose. The registry's
 * own {@code maxAge} garbage collection is the backstop under all of it: a delete this process could
 * not make still ends in a container the orchestrator removes.
 *
 * <p>This is the whole of qits-ci's container vocabulary. {@link CiDaemonStepRunner} is its only
 * caller in production; {@code CiDaemonGateIT} drives it against a real orchestrator.
 */
@ApplicationScoped
public class CiDaemonLauncher {

  private static final Logger LOG = Logger.getLogger(CiDaemonLauncher.class);

  /**
   * The workload every place this class addresses belongs to. One word, this consumer's own: the
   * registry's identity is {@code owner/workload/ref}, so this is what tells a step container from
   * anything else qits-ci might one day ask the orchestrator for — and it is what {@link
   * #destroyAllOwned} scopes the boot reap to.
   */
  static final String WORKLOAD = "ci-step";

  /**
   * How long a teardown may take. Thirty seconds because a delete is at most one docker call behind
   * a registry write, and because it sits on the run worker between one step and the next.
   */
  private static final Duration DESTROY_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The one quick second attempt a log capture gets when nothing answered. Short on purpose: the
   * caller is holding a run worker to record a failure, and a tail is worth a few seconds and not
   * more — the container itself is bounded by the registry's {@code maxAge} whatever happens here.
   */
  private static final Duration DESTROY_RETRY_TIMEOUT = Duration.ofSeconds(5);

  /**
   * How long the launch waits between two attempts at the same place. Five seconds because what it
   * holds through is a token/JWKS window of tens of seconds — see {@link #launchPatience} — so a
   * shorter pause only spends the run worker on refusals nobody has fixed yet, and a longer one
   * spends the window itself.
   */
  private static final Duration LAUNCH_RETRY_PAUSE = Duration.ofSeconds(5);

  /** How many attempts a plain reap gets, and how long it may spend on all of them together. */
  private static final int REAP_ATTEMPTS = 3;

  private static final Duration REAP_BUDGET = Duration.ofSeconds(20);

  /** How long the boot reap waits between attempts, doubling up to {@link #BOOT_REAP_MAX_BACKOFF}. */
  private static final Duration BOOT_REAP_FIRST_BACKOFF = Duration.ofSeconds(1);

  private static final Duration BOOT_REAP_MAX_BACKOFF = Duration.ofSeconds(10);

  /**
   * How much longer than every deadline a step could legitimately spend the registry lets one of
   * these containers live before collecting it itself. Fifteen minutes of slop, so the GC is a
   * backstop for a qits-ci that died rather than a second, competing timeout — see {@link
   * #maxAgeSeconds}.
   */
  private static final Duration MAX_AGE_SLOP = Duration.ofMinutes(15);

  /**
   * Boot order, first half. This observer runs <b>before</b> {@code CiRunService.onStart}, which
   * carries the matching {@code @Priority} one step higher. <b>Move neither alone</b> — see {@link
   * #onStart} for what the order buys.
   *
   * <p>Public only so that {@code BootReconciliationOrderTest}, which sits in the other half's
   * package, can state both numbers in one place instead of restating either as a literal.
   */
  public static final int BOOT_REAP_PRIORITY = 2000;

  /**
   * The label every step container still carries, as an <b>extra</b> label on the spec.
   *
   * <p>It no longer selects anything: the boot reap asks the registry for this owner's own rows and
   * a label filter would be a second, wider answer to the same question — which is the host-wide
   * sweep this cutover removed. It stays because it is what a person reading {@code docker ps}
   * during a build has to go on, and because the orchestrator's own labels name the place rather
   * than the run.
   */
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
   * thing it can ask for is the tail {@link #destroyWithLogs} brings back.
   *
   * <p>{@code exec} rather than a plain call, so the daemon is PID 1 and the removal signals the
   * process that owns the step rather than a shell wrapping it.
   *
   * <p><b>It reaches the container as one list element, never as part of a command line.</b> {@link
   * #buildWorkloadSpec} puts it in {@code args} beside {@code -c}, the orchestrator hands both to
   * {@code ProcessBuilder} unsplit, and no string on either side of the wire is built by
   * concatenating it with anything.
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

  /**
   * The one client, produced by {@code containers/ContainersClientProducer}. Every call it makes is
   * synchronous and bounded, and none of them throws: the four answers are the whole vocabulary.
   */
  @Inject ContainersClient containers;

  /**
   * Who this process <b>is</b> to the orchestrator, and the second half of every place it addresses.
   *
   * <p>It must equal the {@code sub} of the machine token this service presents once the gate is on,
   * because {@code OwnerGuard} compares them — so the shipped default reads
   * {@code quarkus.oidc-client.client-id} and the coupling lives in one place, the key's own comment
   * in the {@code ci} jar's {@code microprofile-config.properties}. It is also the scope: two
   * environments sharing one docker daemon are {@code dev-qits-ci} and {@code prod-qits-ci} and
   * neither one's rows name the other's containers, which is what makes the boot reap safe.
   */
  @ConfigProperty(name = "qits.ci.containers.owner")
  String owner;

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

  /**
   * The other two deadlines a step can legitimately spend, read here for one reason: they are terms
   * of {@link #maxAgeSeconds}, the lifetime the registry collects a forgotten container at.
   */
  @ConfigProperty(name = "qits.ci.daemon-init-timeout-seconds")
  long initTimeoutSeconds;

  @ConfigProperty(name = "qits.ci.step-timeout-grace-seconds")
  long stepTimeoutGraceSeconds;

  /** What a step that declares no {@code timeout-seconds} of its own gets. Same term, same sum. */
  @ConfigProperty(name = "qits.ci.step-timeout-seconds")
  long stepTimeoutSeconds;

  /** How long the boot reap keeps trying an orchestrator that is not answering yet. */
  @ConfigProperty(name = "qits.ci.containers.boot-reap-patience")
  Duration bootReapPatience;

  /**
   * How long a launch holds through an orchestrator that cannot authorize it yet, or cannot be
   * reached at all. The measured window is the trailing edge of a qits-platform-idp cutover — see
   * {@link #launch} and the key's own comment.
   */
  @ConfigProperty(name = "qits.ci.containers.launch-patience")
  Duration launchPatience;

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  @ConfigProperty(name = "qits.ci.memory-limit")
  String memoryLimit;

  @ConfigProperty(name = "qits.ci.pids-limit")
  long pidsLimit;

  @ConfigProperty(name = "qits.ci.cpus")
  String cpus;

  /**
   * qits-artifacts' registry coordinates, injected into every step container so a publish script
   * names no deployment fact of its own. Receiver-named on purpose: they are the artifacts service's
   * address and image namespace, one spelling shared with qits-cd, which derives its pull references
   * from the same two values. Neither is dialled by <em>this</em> process — see {@link
   * #buildWorkloadSpec}.
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
   * #buildWorkloadSpec}.
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
   * way: the orchestrator mounts the host's docker socket. See {@link #buildWorkloadSpec}.
   *
   * <p>{@code user} is the step's other declaration, and the only other thing about a step that
   * reaches the sandbox. Empty means the image's own default; a name means {@code --user}, which is
   * the only place a step's user can be set — the container runs {@code --cap-drop=ALL} and can
   * neither {@code su} nor {@code chown} once it is running. The parser refuses it beside
   * {@code docker}, so the two never arrive together.
   *
   * <p>{@code stepTimeoutSeconds} is the step's own deadline, carried here for one purpose: it is a
   * term of the lifetime the registry will collect this container at ({@link #maxAgeSeconds}). Zero
   * means the pipeline declared none, and the configured default stands in — a probe, which has no
   * step at all, passes zero.
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
      int stepTimeoutSeconds,
      boolean docker,
      String user,
      Map<String, String> env) {}

  /**
   * Whether the container started, under what name, and what the orchestrator said if it did not. A
   * failed launch is its own recorded outcome — "the container was refused" is not "the step
   * failed", and neither is "nothing answered".
   */
  public record Launched(boolean started, String containerName, String error) {}

  /**
   * The boot half of the fail-and-reap reconciliation. {@code CiRunService.onStart} already fails
   * runs a crash left {@code RUNNING}; this removes the containers those runs left behind — every
   * one of <b>this owner's</b> {@code ci-step} places created before this process came up. The
   * registry starts empty, so a daemon from a previous life that manages to dial in presents a
   * secret this process does not know and is closed 1008 — its container is already gone or about
   * to be.
   *
   * <p>It is a second observer rather than an edit to {@code CiRunService.onStart} because that
   * method lives in the {@code ci} module, which has no web stack and must not gain a dependency on
   * this one. The two halves run at the same event and mean one thing together: no run claims to be
   * executing, and nothing it started is still running.
   *
   * <p><b>This half runs first, and the order still has to be stated.</b> {@code
   * CiRunService.sweepInterrupted} does not only write rows — it puts work back on the run worker,
   * restarting every interrupted event run and re-enqueueing every {@code QUEUED} one, and that
   * worker asks for step containers as soon as it has work. The scope narrowed with the cutover
   * (this owner's rows, not every labelled container on the daemon) but the window did not close by
   * itself: a container a restarted run had just asked for is <em>also</em> one of this owner's, so
   * a sweep-then-reap order could still take it. What the cutover added is a second net rather than
   * a replacement — {@code createdBefore} is stamped once, at this method's entry, so a place
   * created afterwards is outside the set by construction. Order first, instant second, and neither
   * makes the other unnecessary: the order is what keeps a slow reap from meeting a fast worker,
   * and the instant is what holds if the two ever ran concurrently. {@code @Priority} on both
   * observers is what encodes the order — this one is {@link #BOOT_REAP_PRIORITY}, {@code
   * CiRunService.onStart} is the higher number, and <b>neither moves alone</b>. {@code
   * BootReconciliationOrderTest} holds it.
   *
   * <p>The instant is captured <b>here</b> rather than inside {@link #destroyAllOwned}, and that is
   * the same decision: the reap retries a patience window long, and an instant re-read per attempt
   * would widen the set with every retry until it included what this boot had already started.
   *
   * <p>Skipped under {@code TEST}, like the runner's own startup observer: the suites reach no
   * orchestrator by intent, and a test app must not delete another process's containers to prove
   * it.
   */
  void onStart(@Observes @Priority(BOOT_REAP_PRIORITY) StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    int reaped = destroyAllOwned(Instant.now());
    if (reaped > 0) {
      LOG.infof("Removed %d orphaned CI step container(s) left by a previous shutdown", reaped);
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

  /**
   * Ask the orchestrator to put the step container at this run's own place. The daemon dials back;
   * nothing dials in.
   *
   * <p><b>One attempt per answer the orchestrator gave about the request, and a patient loop for
   * the two answers that are about nothing but the moment.</b> This used to be a single attempt for
   * every outcome, on the reasoning that a second attempt against a service that answered would be
   * a second workload. That reasoning holds for every refusal that says something about the request
   * — {@code SPEC_CONFLICT}, {@code IMAGE_MISSING}, a 400 on a value — and those stay exactly one
   * attempt. It never applied to the two below, and the 2026-08-12 rebootstrap measured what that
   * cost: the deploy train replaced qits-platform-idp, and the next three push builds died at step
   * launch with {@code orchestrator refused: refused 401} while the following ones passed. A build
   * failed for a reason that had nothing to do with its commit.
   *
   * <ul>
   *   <li><b>401 and 403</b> — the token this process presents was minted by an idp that has just
   *       been replaced, or the orchestrator's JWKS is a cutover behind. Each attempt asks the
   *       {@code TokenSource} again, so a fresh post-cutover token is picked up by retrying and by
   *       nothing else.
   *   <li><b>Nothing answered</b> — the orchestrator is restarting. Retrying is safe here for a
   *       reason a bare {@code docker run} never had: {@code ensure} is a PUT per {@code
   *       (owner, workload, ref)} and the ref is this step's own container name, so a second attempt
   *       addresses the same place. A container the first attempt created and could not tell us
   *       about is adopted rather than duplicated.
   * </ul>
   *
   * <p><b>The window sits BESIDE the launch deadline, not inside it.</b> {@link #launchTimeout()}
   * is one attempt's deadline and stays exactly that — it is mostly an image pull, and shortening
   * it to fit a retry budget would turn a cold pull into a failed launch. {@code
   * qits.ci.containers.launch-patience} bounds when a <em>fresh</em> attempt may start, so the worst
   * case is the patience plus one whole launch deadline (PT90S + 60s as shipped). That is
   * deliberately far inside {@link #MAX_AGE_SLOP}'s fifteen minutes: an unreachable first attempt
   * may have created the container, its {@code maxAge} clock starts there, and the slop is what
   * keeps the registry's GC a backstop rather than a second timeout even then. Nothing downstream
   * shifts — {@code CiDaemonStepRunner} starts its register deadline when this method returns.
   *
   * <p><b>A 2xx whose container is not there is a failed launch.</b> The wire contract is explicit
   * that an {@code ensure} whose container did not start is a true answer rather than a failed
   * request — the row exists, it says {@code MISSING}, and it carries what docker said — so the
   * status alone does not answer this method's question. Reading such an answer as "started" would
   * cost the run its register deadline (a minute of a build slot) and then record {@code
   * NEVER_STARTED} for a container that never existed, which is the wrong outcome as well as the
   * slow one. It is not retried either: something answered about this very container.
   */
  public Launched launch(LaunchSpec spec) {
    CiIdentifiers.requireRepoId(spec.repoId());
    CiIdentifiers.requireBranch(spec.branch());
    CiIdentifiers.requireSha(spec.sha());
    // The image comes from the repository's own config rather than from the intake, but it reaches a
    // docker argv on the far side all the same, so it is checked here and to the same standard. The
    // orchestrator checks it again; two checkpoints, one rule each side owns.
    CiIdentifiers.requireImage(spec.image());

    String name = containerName(spec.runId(), spec.stepIndex());
    // Assembled once: every attempt asks for the same place under the same spec, which is what makes
    // the PUT idempotent rather than a second workload.
    EnsureRequest request = buildWorkloadSpec(spec);
    Instant giveUpAt = Instant.now().plus(launchPatience);
    // Never pause past the window itself: a pause longer than the patience would make a short
    // patience mean one attempt while looking like a window, which is the shape a test cannot see.
    Duration pause =
        LAUNCH_RETRY_PAUSE.compareTo(launchPatience) > 0 ? launchPatience : LAUNCH_RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      ContainersAnswer<Envelope> answer =
          containers.ensure(owner, WORKLOAD, name, request, launchTimeout());
      if (answer.succeeded()) {
        return started(spec, name, answer.value());
      }
      if (holdThrough(answer) && Instant.now().isBefore(giveUpAt) && sleep(pause)) {
        LOG.infof(
            "Attempt %d to start step container %s did not land (%s) — asking again, holding through"
                + " the window",
            attempts, name, answer.detail());
        continue;
      }
      return notStarted(name, answer, attempts);
    }
  }

  /**
   * The two answers another attempt could change, and the one place that decision is made.
   *
   * <p><b>401 and 403 are in it, and that is the 2026-08-12 lesson.</b> They read like statements
   * about the request — the owner guard said no — and for a stable deployment they are. Across an
   * idp cutover they are a statement about the moment instead: the same call with the same owner
   * succeeds a minute later, because the token or the key that validates it has been replaced.
   * There is no way to tell the two apart from here, so the patient reading is the safe one — every
   * call this predicate governs is idempotent, so a retry that was never needed costs one request.
   *
   * <p>Everything else is an answer about the request and is taken at its word here: {@code
   * SPEC_CONFLICT}, {@code IMAGE_MISSING}, a 400 on a value, a 404 saying the place is already gone
   * — and a 5xx too, which {@link #reap} adds on its own because a teardown may spend a few seconds
   * on a service whose database is down while a launch may not spend a build slot on one.
   */
  private static boolean holdThrough(ContainersAnswer<?> answer) {
    if (answer.unreachable()) {
      return true;
    }
    return answer instanceof ContainersAnswer.Refused<?> refused
        && (refused.status() == 401 || refused.status() == 403);
  }

  /** What a launch that never landed is recorded as — the same two sentences it always was. */
  private static Launched notStarted(String name, ContainersAnswer<Envelope> answer, int attempts) {
    if (answer instanceof ContainersAnswer.Unreachable<Envelope> unreachable) {
      // Nothing answered, so nothing is known about the workload — including whether one exists.
      LOG.warnf(
          "Could not reach qits-containers to start %s after %d attempt(s): %s",
          name, attempts, unreachable.cause());
      return new Launched(false, name, "orchestrator unreachable: " + unreachable.cause());
    }
    // Something answered and said no: a 409 IMAGE_MISSING, a 400 on a value, a 403 from the owner
    // guard the patience window could not outlast. The code travels into the recorded detail because
    // it is what tells an operator whether to push an image or to fix a token.
    LOG.warnf(
        "Could not start step container %s after %d attempt(s): %s", name, attempts, answer.detail());
    return new Launched(false, name, "orchestrator refused: " + answer.detail());
  }

  /** A 2xx, read for whether a container is actually there — see {@link #launch}'s last paragraph. */
  private Launched started(LaunchSpec spec, String name, Envelope envelope) {
    Observed observed = envelope == null || envelope.state() == null ? null : envelope.state().observed();
    if (observed == Observed.MISSING || observed == Observed.GONE) {
      String detail = envelope.detail() == null ? "" : envelope.detail();
      LOG.warnf("The step container %s was not started: %s", name, detail);
      return new Launched(false, name, "the container did not start: " + detail);
    }
    LOG.debugf("Started step container %s for run %s step %d", name, spec.runId(), spec.stepIndex());
    return new Launched(true, name, null);
  }

  /**
   * Take the container away and bring back what it printed, in <b>one</b> call.
   *
   * <p>This is the bootstrap's error report and the only thing a container that never registered has
   * to say, so it has to be read before the removal — and it used to be two calls in that order, a
   * {@code logs} then a {@code rm}, with the ordering held by the caller. Asking for the tail on the
   * delete moves the ordering to the far side of the wire, where nothing between the two can lose
   * it.
   *
   * <p><b>A failure here is never a failure of the step.</b> The caller is already recording why a
   * step failed; what this can add is either the container's own words or a sentence saying they
   * could not be fetched. The container itself is not leaked either way — the registry's {@code
   * maxAge} collects it, which is the whole reason {@link #maxAgeSeconds} is a term rather than a
   * guess.
   *
   * <p><b>The tail is bounded again on this side.</b> The orchestrator bounds what it returns, and
   * that is not the point: this is the last untrusted boundary before the text becomes a row, the
   * response is as attacker-shaped as the step output inside it, and a bound applied only by the
   * sender is a bound a buggy or hostile sender does not apply. {@code qits.ci.output-max-chars} is
   * the same budget the relay and the persisted tail already share.
   */
  public String destroyWithLogs(String containerName) {
    ContainersAnswer<DeleteOutcome> answer =
        containers.delete(owner, WORKLOAD, containerName, false, true, DESTROY_TIMEOUT);
    if (holdThrough(answer)) {
      // One quick second attempt, on a short deadline: a connection refused in the first millisecond
      // is worth another try, and so is the 401 an idp cutover leaves behind — the delete is
      // idempotent and the second attempt asks the TokenSource again. The caller is a run worker
      // holding a build slot to record a failure it already knows about, so it gets one attempt and
      // not a window.
      answer = containers.delete(owner, WORKLOAD, containerName, false, true, DESTROY_RETRY_TIMEOUT);
    }
    if (!answer.succeeded()) {
      LOG.warnf("Could not capture the log tail of %s: %s", containerName, answer.detail());
      return "log tail unavailable: " + answer.detail();
    }
    DeleteOutcome outcome = answer.value();
    return bounded(outcome == null ? null : outcome.logTail());
  }

  /**
   * Remove the container, running or not, wanting nothing back. Every teardown path ends here.
   *
   * <p><b>Idempotent, which is what lets it be retried.</b> A place that was already absent answers
   * {@code existed=false} and a 404 from anything in front of the service means the same thing, so
   * both are success: the caller asked for nothing to be there and nothing is.
   *
   * <p><b>Bounded retry, then a WARN and nothing else.</b> A reap failure must never fail a green
   * step — the step is over and its result is recorded — so the attempts are few, the budget is
   * short, and only the answers another attempt could change are retried: nothing answered, a 5xx,
   * and the 401/403 of an idp cutover ({@link #holdThrough}). Every other 4xx is a statement about
   * the request and retrying it is a way to spend a run worker twenty seconds for nothing. What
   * catches whatever is left is the registry's own {@code maxAge} garbage collection, which is the
   * backstop this whole class now leans on.
   */
  public void reap(String containerName) {
    Duration deadline = REAP_BUDGET.dividedBy(REAP_ATTEMPTS);
    for (int attempt = 1; attempt <= REAP_ATTEMPTS; attempt++) {
      ContainersAnswer<DeleteOutcome> answer =
          containers.delete(owner, WORKLOAD, containerName, false, false, deadline);
      if (answer.succeeded()) {
        return;
      }
      if (answer instanceof ContainersAnswer.Refused<DeleteOutcome> refused) {
        if (refused.status() == 404) {
          // Nothing is there, which is what was asked for.
          return;
        }
        if (refused.status() < 500 && !holdThrough(answer)) {
          LOG.warnf("Could not remove step container %s: %s", containerName, refused.detail());
          return;
        }
      }
      if (attempt == REAP_ATTEMPTS) {
        LOG.warnf(
            "Could not remove step container %s after %d attempts (%s) — leaving it to the"
                + " orchestrator's GC",
            containerName, REAP_ATTEMPTS, answer.detail());
      }
    }
  }

  /**
   * Remove every one of this owner's step containers created before an instant. Returns how many
   * were removed.
   *
   * <p><b>Scoped to this owner's rows, and that is the cutover's whole point.</b> It used to be a
   * host-wide {@code docker ps --filter label=…}, which removed every labelled container on the
   * daemon — including one another qits-ci was running a step in right now — so "one qits-ci per
   * docker daemon" was a deployment constraint an operator had to know. The orchestrator's registry
   * names places by owner, two owners cannot see each other's rows, and the constraint is gone: what
   * is left is that two instances must not share an owner, which is one config key rather than a
   * property of the host.
   *
   * <p><b>This one needs no classifier and asks for none.</b> It retries every answer that is not a
   * success until its patience runs out, so the 401 an idp cutover leaves behind is already held
   * through. {@link #holdThrough} is the narrower rule the two per-container paths need, because
   * they sit on a run worker rather than on a boot.
   *
   * <p><b>{@code createdBefore} is required and is the second net.</b> The caller stamps it once, at
   * boot, so a container this boot goes on to start is outside the set no matter how long this takes
   * — see {@link #onStart}.
   *
   * <p><b>An orchestrator that is not up yet is waited for, and then given up on.</b> Both services
   * come up in one compose and either order is legal, so a refused connection at boot is ordinary
   * rather than a failure; the patience window is what turns it into a delay. Past it this returns 0
   * and warns, and <b>boot proceeds</b> — the same stance the docker-briefly-down sweep took, for
   * the same reason: a process that refuses to start because a teardown could not run is a process
   * that cannot recover the runs it is holding. What covers the orphans then is the registry's own
   * {@code maxAge} GC.
   */
  public int destroyAllOwned(Instant createdBefore) {
    Instant giveUpAt = Instant.now().plus(bootReapPatience);
    Duration backoff = BOOT_REAP_FIRST_BACKOFF;
    while (true) {
      ContainersAnswer<List<Destroyed>> answer =
          containers.destroyAll(owner, WORKLOAD, createdBefore, DESTROY_TIMEOUT);
      if (answer.succeeded()) {
        List<Destroyed> destroyed = answer.value() == null ? List.of() : answer.value();
        return (int) destroyed.stream().filter(Destroyed::removed).count();
      }
      if (Instant.now().isAfter(giveUpAt)) {
        LOG.warnf(
            "Could not reap this boot's orphaned CI step containers (%s) — orphans left to the"
                + " orchestrator's GC",
            answer.detail());
        return 0;
      }
      if (!sleep(backoff)) {
        return 0;
      }
      backoff = backoff.multipliedBy(2);
      if (backoff.compareTo(BOOT_REAP_MAX_BACKOFF) > 0) {
        backoff = BOOT_REAP_MAX_BACKOFF;
      }
    }
  }

  /** Wait, or report that this thread is being asked to stop — in which case the reap is over. */
  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** The bound this service applies to every piece of step output, applied once more at the wire. */
  private String bounded(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= outputMaxChars
        ? text
        : text.substring(text.length() - outputMaxChars);
  }

  /**
   * How long the registry may let this container live before collecting it itself.
   *
   * <p>The sum of every deadline a step can legitimately spend — register, initialize, the step's
   * own, and the host's grace behind it — plus {@link #MAX_AGE_SLOP}. The slop is what keeps this a
   * <b>backstop rather than a second timeout</b>: every one of those deadlines is enforced by
   * something that reports what it enforced, and a {@code maxAge} that could fire first would take a
   * container away mid-step and leave the host reporting a lost socket instead of a timeout.
   */
  long maxAgeSeconds(LaunchSpec spec) {
    long step = spec.stepTimeoutSeconds() > 0 ? spec.stepTimeoutSeconds() : stepTimeoutSeconds;
    return registerTimeoutSeconds
        + initTimeoutSeconds
        + step
        + stepTimeoutGraceSeconds
        + MAX_AGE_SLOP.toSeconds();
  }

  /**
   * The whole workload spec, as the orchestrator's wire spells it. Two paragraphs of it are
   * load-bearing enough that a field lost in a refactor is a security regression, so {@code
   * CiDaemonLauncherTest} asserts the request literally — including the <b>absence</b> of the docker
   * socket for a step that did not ask for it.
   *
   * <p><b>The socket is the one privilege a repository can ask for.</b> A step declaring {@code
   * docker: true} sets {@code hostDockerSocket}, and the orchestrator bind-mounts the host's socket
   * at the path the step image's CLI looks at by default — which is how publishing works: the step's
   * CLI streams its build context to the <em>host's</em> daemon, which builds, tags and pushes.
   * Where that path is is the orchestrator's deployment fact now, not this service's, which is why
   * {@code qits.ci.docker-socket-path} is gone. The sandbox stays exactly as it is for such a step —
   * {@code capDropAll} and {@code noNewPrivileges} cost a socket <em>client</em> nothing, and keeping
   * them unconditional keeps them meaning what they mean for every step that does not opt in. They
   * also do not make the opt-in safe: a step holding this socket is <b>root-equivalent on the
   * host</b>, because those flags fence the step's own process tree and not what the daemon will do
   * on its behalf. That is accepted for the POC and it is per step, declared in the repository's
   * config where a diff shows it — see {@code AGENTS.md}'s untrusted-input section.
   *
   * <p><b>The container name is this service's own and travels as {@code explicitName}.</b> The
   * orchestrator would derive one; qits-ci names its containers so that a person reading {@code
   * docker ps} during a build sees the run, and so that the name and the {@code ref} are the same
   * string — see {@link #launch}.
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
  EnsureRequest buildWorkloadSpec(LaunchSpec spec) {
    Map<String, String> env = new LinkedHashMap<>();
    // The contract, as environment. The daemon needs all of it before a socket exists, which is why
    // none of it is a message.
    env.put("QITS_CI_DAEMON_ID", value(spec.daemonId()));
    env.put("QITS_CI_DAEMON_SECRET", value(spec.secret()));
    env.put("QITS_CI_DAEMON_URL", value(containerDaemonUrl));
    env.put("QITS_CI_DAEMON_BINARY_URL", value(spec.daemonBinaryUrl()));
    env.put("QITS_CI_REPOSITORY_URL", value(cloneUrl(spec.repoId())));
    env.put("QITS_CI_BRANCH", value(spec.branch()));
    env.put("QITS_CI_SHA", value(spec.sha()));
    env.put("QITS_CI_REPO_ID", value(spec.repoId()));
    // For the step script rather than the daemon: the de-facto convention tooling checks for
    // non-interactive mode, and one that says which CI this is.
    env.put("CI", "true");
    env.put("QITS_CI", "true");
    // Also for the script: where a published image goes. Every container gets them, because "which
    // registry" must never be a literal in a repository's pipeline. Together with $QITS_CI_SHA above
    // they are the whole of the tag convention qits-cd pulls by,
    // <registry>/<repository>/<application>:<sha>.
    env.put("QITS_REGISTRY", value(artifactsRegistryHost));
    env.put("QITS_IMAGE_REPOSITORY", value(artifactsImageRepository));
    // And where npm packages come from and go to. Unlike the two above, these are dialled by this
    // container, on this network — a publish here is an ordinary HTTP step needing no socket.
    env.put("QITS_NPM_REGISTRY_URL", value(artifactsNpmHostedUrl));
    env.put("QITS_NPM_PROXY_URL", value(artifactsNpmProxyUrl));
    env.put("QITS_MAVEN_REGISTRY_URL", value(artifactsMavenRegistryUrl));
    env.put("QITS_DOCS_URL", value(artifactsDocsUrl));
    // And where a step asks for its own repository to be released — same network, same reading of
    // "reachable from where" as the npm pair.
    env.put("QITS_WORKSPACES_URL", value(workspacesUrl));
    // Run-scoped extras, LAST and in sorted key order. Today these are the four QITS_EVENT_* of an
    // event-triggered run and the map is empty on every push; none of them is ever repo-authored.
    // Last is the construction the argv had, where a repeated --env meant the later one won, so a
    // map's later put means exactly what the old argv meant. Sorted because the whole request is
    // asserted literally by CiDaemonLauncherTest, and a set's iteration order is not a thing to
    // assert against.
    for (Map.Entry<String, String> extra : new TreeMap<>(spec.env()).entrySet()) {
      env.put(extra.getKey(), value(extra.getValue()));
    }

    Spec workload =
        new Spec(
            spec.image(),
            // The entrypoint and the bootstrap, as two lists rather than a command line. Nothing is
            // concatenated on either side of the wire, so the zero-interpolation property BOOTSTRAP
            // has always claimed now holds BY CONSTRUCTION rather than by inspection of an argv.
            List.of("/bin/sh"),
            List.of("-c", BOOTSTRAP),
            env,
            // The human hint. It selects nothing any more — see RUN_LABEL.
            Map.of(RUN_LABEL, value(spec.runId())),
            network,
            null,
            List.of("host.docker.internal:host-gateway"),
            null,
            null,
            // The declared opt-in, and the only thing about a step that ever changes this request.
            spec.docker(),
            // The step's script is repo-controlled: drop privileges and bound the blast radius. The
            // daemon runs inside this sandbox and the script is its child, so these bound both.
            new Security(true, true, memoryLimit, memoryLimit, pidsLimit, cpus),
            null,
            containerName(spec.runId(), spec.stepIndex()),
            // The other declared opt-in, and the reason it is here rather than in the script: the
            // sandbox above takes CAP_SETUID, CAP_SETGID and CAP_CHOWN away, so `su` and `chown`
            // both fail inside the container whatever it tries. Empty is the image's own default.
            // The parser refuses this beside `docker`, so a socket-holding step is always root.
            value(spec.user()));
    // EPHEMERAL: a step container runs once and exits, so a recreate under a changed spec is a
    // refusal rather than a restart — which is right for a place named after one step of one run.
    return EnsureRequest.of(workload, Policy.ephemeral(maxAgeSeconds(spec)));
  }

  private static String value(String text) {
    return text == null ? "" : text;
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
   * One name shape, shared by the launch and every teardown — and it is the {@code ref} as well.
   *
   * <p><b>The name IS the place.</b> The registry's identity is {@code owner/workload/ref} and one
   * live row per triple is the invariant, so the ref has to be the one string that means "this step
   * of this run" and nothing else. This name already was that string: it is derived from the whole
   * {@code runId} plus the step index, it is deterministic, and a retry of the same step therefore
   * addresses the same row rather than making a second one. The alternative spellings —
   * {@code runId + "-" + stepIndex}, a fresh UUID — would each be a second identity to keep in step
   * with this one, and the honest ref is the name a person reads in {@code docker ps}.
   *
   * <p>It is also inside {@code ContainersIdentifiers}' charset for a ref by construction:
   * lowercase, alphanumerics and dashes, no leading dash, far under the 190-character cap.
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
   * names the same container, which matters because {@link #reap} and {@link #destroyWithLogs} have
   * to address what {@link #launch} put there. Its output is hex digits only, already inside
   * docker's container-name charset ({@code [a-zA-Z0-9][a-zA-Z0-9_.-]*}), so nothing further needs
   * sanitizing.
   */
  static String containerName(String runId, int stepIndex) {
    String shortRun = runId.length() > 8 ? runId.substring(0, 8) : runId;
    String disambiguator = Integer.toHexString(runId.hashCode());
    return "qits-ci-" + shortRun + "-" + disambiguator + "-" + stepIndex;
  }
}
