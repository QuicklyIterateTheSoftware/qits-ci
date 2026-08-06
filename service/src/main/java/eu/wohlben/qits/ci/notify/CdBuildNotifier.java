package eu.wohlben.qits.ci.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CdNotifier;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Delivers a green run to qits-platform-deployments' HTTP intake: one {@code {runId, repoId, branch,
 * commitSha}} POST per {@code SUCCESS} run — the {@code CiPostReceiveNotifier} shape, one hop
 * further down the chain (the git host announces pushes to ci the same way ci announces green builds
 * to the deployer).
 *
 * <p>Fire-and-forget: the caller is a run worker, between one run and the next,
 * so this must never block or throw — failures are swallowed at debug (a missed event means one
 * deployment does not happen; the next green build carries the branch forward). The flip side of
 * that idiom is the documented hazard: a path mismatch with the receiver's {@code
 * /platform-deployments/api/events/build-succeeded} raises no error anywhere and deployments simply
 * stop, which is why both repos pin the literal and both suites assert their own absolute address.
 *
 * <p>It carries a machine bearer ({@code aud=qits-platform-deployments}) when a deployment has given
 * this service client credentials at qits-idp, and nothing when it has not — see {@link CdBearer}.
 * Both ends move independently: the receiver decides whether it demands one, this side decides
 * whether it presents one, and the unconfigured default is the call as it always was.
 *
 * <p>It lives in {@code service/} because the {@code ci} module is web-free; the seam it implements
 * is {@link CdNotifier} in {@code ci/control}, and zero implementations is a supported
 * configuration.
 */
@ApplicationScoped
public class CdBuildNotifier implements CdNotifier {

  private static final Logger LOG = Logger.getLogger(CdBuildNotifier.class);

  /**
   * An <b>instance</b> field, not a static one — the native-image constraint
   * {@code CiPostReceiveNotifier} documents at length: a static {@code HttpClient} is created at
   * image build time and native-image refuses the heap it lands in. {@code @ApplicationScoped}
   * keeps it one client per process.
   */
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.pd.intake-url")
  String intakeUrl;

  @Inject ObjectMapper objectMapper;

  /**
   * The machine credential, when a deployment has configured one — see {@link CdBearer}. Left null
   * by the plain-JUnit fixture, which is the "no credentials configured" case and the shape this
   * call has always had.
   */
  @Inject CdBearer bearer;

  @Override
  public void onRunSucceeded(String runId, String repoId, String branch, String commitSha) {
    try {
      post(runId, repoId, branch, commitSha);
    } catch (Exception e) {
      LOG.debugf("CD notification for %s@%s skipped: %s", repoId, branch, e.toString());
    }
  }

  private void post(String runId, String repoId, String branch, String commitSha) throws Exception {
    String body =
        objectMapper.writeValueAsString(
            Map.of("runId", runId, "repoId", repoId, "branch", branch, "commitSha", commitSha));
    // Two async steps, not one: fetching the token must not park the run worker any more than
    // sending must. With no credential configured the first step is already-resolved and the send
    // happens on this thread, which is exactly the behaviour this call has always had.
    credential()
        .subscribe()
        .with(
            authorization -> send(body, authorization, repoId, branch),
            failure ->
                LOG.debugf("CD notification for %s@%s has no token: %s", repoId, branch, failure));
  }

  private Uni<Optional<String>> credential() {
    return bearer == null ? Uni.createFrom().item(Optional.empty()) : bearer.bearer();
  }

  private void send(String body, Optional<String> authorization, String repoId, String branch) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(intakeUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    authorization.ifPresent(value -> request.header("Authorization", value));
    client
        .sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
        .whenComplete(
            (response, failure) -> {
              if (failure != null) {
                LOG.debugf("CD notification for %s@%s failed: %s", repoId, branch, failure);
              } else if (response.statusCode() >= 400) {
                LOG.debugf(
                    "CD notification for %s@%s rejected: %d", repoId, branch, response.statusCode());
              }
            });
  }
}
