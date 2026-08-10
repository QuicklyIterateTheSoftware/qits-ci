package eu.wohlben.qits.ci.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.PdNotifier;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Delivers a green run to qits-platform-deployments' HTTP intake: one {@code {runId, repoId, branch,
 * commitSha}} POST per {@code SUCCESS} run — the {@code CiPostReceiveNotifier} shape, one hop
 * further down the chain (the git host announces pushes to ci the same way ci announces green builds
 * to the deployer).
 *
 * <p><b>Never blocks and never throws.</b> The caller is a run worker, between one run and the next,
 * so everything here is asynchronous and every failure ends in a log line. That constraint has not
 * moved and must not.
 *
 * <p><b>A failed delivery is retried, because one lost POST cost a deployment.</b> The call used to
 * be a single attempt whose failure was swallowed at debug. Measured on a platform bootstrap:
 * qits-platform-idp was redeployed a few minutes before a green qits-platform-docs run finished, the
 * announcement for that run left no trace at the deployer 21 seconds after an identical delivery had
 * been accepted, and the same POST replayed by hand minutes later was accepted with 202. One
 * transient refusal — most plausibly a stale bearer or JWKS window right after the idp cutover —
 * meant the deployment simply never happened, with nothing anywhere to say so, and the bootstrap
 * waited an hour.
 *
 * <p>So an attempt that cannot connect, or that answers anything but 2xx, is retried on this bean's
 * own scheduler after {@link #PRODUCTION_RETRY_DELAYS} — 5s, 15s, 45s, 2m, roughly three minutes in
 * all, which is long enough to cover a container cutover or a token handoff and short enough that a
 * deployment still follows its build. The credential is fetched again per attempt (see {@link
 * PdBearer}, whose oidc-client refreshes as needed), so a retry after an idp cutover presents a
 * fresh token rather than the one that was just refused.
 *
 * <p>Two consequences worth having in front of you. A refusal that arrives <em>after</em> the
 * receiver has already acted delivers the same {@code runId} twice — the same commit deployed again,
 * which is the cheaper of the two errors. And giving up is a WARN naming the repository, the branch
 * and the last failure, so a deployment whose intake url is wrong, or that runs with no deployer at
 * all, now costs one warning per green build instead of one invisible debug line. The silence is
 * what made the measured loss expensive; the noise is the price of ending it.
 *
 * <p>Past that window delivery is still at-most-once: a missed event means one deployment does not
 * happen, and the next green build carries the branch forward. The hazard the idiom always had is
 * unchanged too — a path mismatch with the receiver's {@code
 * /platform-deployments/api/events/build-succeeded} breaks nothing but the deployment, which is why
 * both repos pin the literal and both suites assert their own absolute address.
 *
 * <p>It carries a machine bearer ({@code aud=qits-platform-deployments}) when a deployment has given
 * this service client credentials at qits-idp, and nothing when it has not — see {@link PdBearer}.
 * Both ends move independently: the receiver decides whether it demands one, this side decides
 * whether it presents one, and the unconfigured default is the call as it always was.
 *
 * <p>It lives in {@code service/} because the {@code ci} module is web-free; the seam it implements
 * is {@link PdNotifier} in {@code ci/control}, and zero implementations is a supported
 * configuration.
 */
@ApplicationScoped
public class PdBuildNotifier implements PdNotifier {

  private static final Logger LOG = Logger.getLogger(PdBuildNotifier.class);

  /**
   * How long to wait before each retry. Four entries, so five attempts in all, spanning about three
   * minutes — an idp or deployer container cutover is over well inside that, and a run that has to
   * wait three minutes for its deployment is still deployed.
   */
  static final List<Duration> PRODUCTION_RETRY_DELAYS =
      List.of(
          Duration.ofSeconds(5),
          Duration.ofSeconds(15),
          Duration.ofSeconds(45),
          Duration.ofMinutes(2));

  /**
   * An <b>instance</b> field, not a static one — the native-image constraint
   * {@code CiPostReceiveNotifier} documents at length: a static {@code HttpClient} is created at
   * image build time and native-image refuses the heap it lands in. {@code @ApplicationScoped}
   * keeps it one client per process.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  /**
   * Where a retry waits. An instance field for the same native-image reason as the client above, and
   * a daemon thread because a pending retry must never hold the process open at shutdown. One thread
   * is enough: it only sleeps and then hands the send back to the client's own executor.
   */
  private final ScheduledExecutorService retries =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "pd-notify-retry");
            thread.setDaemon(true);
            return thread;
          });

  /** The schedule in use. Production ships {@link #PRODUCTION_RETRY_DELAYS}; tests use millis. */
  List<Duration> retryDelays = PRODUCTION_RETRY_DELAYS;

  @ConfigProperty(name = "qits.platform.deployments.intake-url")
  String intakeUrl;

  @Inject ObjectMapper objectMapper;

  /**
   * The machine credential, when a deployment has configured one — see {@link PdBearer}. Left null
   * by the plain-JUnit fixture, which is the "no credentials configured" case and the shape this
   * call has always had.
   */
  @Inject PdBearer bearer;

  /** What one announcement needs to be retried: the wire body and enough to name it in a log. */
  private record Announcement(String body, String repoId, String branch) {}

  @Override
  public void onRunSucceeded(String runId, String repoId, String branch, String commitSha) {
    try {
      String body =
          objectMapper.writeValueAsString(
              Map.of("runId", runId, "repoId", repoId, "branch", branch, "commitSha", commitSha));
      attempt(new Announcement(body, repoId, branch), 0);
    } catch (Exception e) {
      // A body that cannot be written is not transient, so there is nothing to retry.
      LOG.warnf("Deploy announcement for %s@%s not sent: %s", repoId, branch, e.toString());
    }
  }

  /**
   * One attempt: fetch the credential, then send. Both steps are async, so nothing here parks the
   * thread it is called on — the run worker for the first attempt, the retry thread after that.
   *
   * <p>The fetch is inside the attempt rather than before the first one on purpose: an idp cutover
   * refuses the token as readily as the intake refuses the POST, and a retry that reused the token
   * of a failed attempt would present the credential that had just been refused.
   */
  private void attempt(Announcement announcement, int index) {
    credential()
        .subscribe()
        .with(
            authorization -> send(announcement, authorization, index),
            failure -> retryOrGiveUp(announcement, index, "no token: " + failure));
  }

  private Uni<Optional<String>> credential() {
    return bearer == null ? Uni.createFrom().item(Optional.empty()) : bearer.bearer();
  }

  private void send(Announcement announcement, Optional<String> authorization, int index) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(intakeUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(announcement.body()));
    authorization.ifPresent(value -> request.header("Authorization", value));
    client
        .sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, failure) -> {
              if (failure != null) {
                retryOrGiveUp(announcement, index, failure.toString());
              } else if (response.statusCode() / 100 != 2) {
                retryOrGiveUp(announcement, index, "HTTP " + response.statusCode());
              } else if (index > 0) {
                LOG.infof(
                    "Deploy announcement for %s@%s accepted on attempt %d",
                    announcement.repoId(), announcement.branch(), index + 1);
              }
            });
  }

  private void retryOrGiveUp(Announcement announcement, int index, String failure) {
    if (index >= retryDelays.size()) {
      LOG.warnf(
          "Deploy announcement for %s@%s given up after %d attempts, no deployment will happen: %s",
          announcement.repoId(), announcement.branch(), index + 1, failure);
      return;
    }
    Duration delay = retryDelays.get(index);
    LOG.debugf(
        "Deploy announcement for %s@%s failed on attempt %d, retrying in %s: %s",
        announcement.repoId(), announcement.branch(), index + 1, delay, failure);
    try {
      retries.schedule(
          () -> attempt(announcement, index + 1), delay.toMillis(), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // The process is shutting down. The pending retries go with it, which is the same at-most-once
      // boundary a restart has always had.
      LOG.warnf(
          "Deploy announcement for %s@%s dropped at shutdown: %s",
          announcement.repoId(), announcement.branch(), failure);
    }
  }

  /** Also the seam the suite closes, so a test's pending retries do not outlive its fixture. */
  @PreDestroy
  void stopRetrying() {
    retries.shutdownNow();
  }
}
