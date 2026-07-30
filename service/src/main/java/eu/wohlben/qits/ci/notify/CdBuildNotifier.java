package eu.wohlben.qits.ci.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CdNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Delivers a green run to qits-cd's HTTP intake: one {@code {runId, repoId, branch, commitSha}}
 * POST per {@code SUCCESS} run — the {@code CiPostReceiveNotifier} shape, one hop further down the
 * chain (the git host announces pushes to ci the same way ci announces green builds to cd).
 *
 * <p>Fire-and-forget: the caller is the single-threaded run worker, between one run and the next,
 * so this must never block or throw — failures are swallowed at debug (a missed event means one
 * deployment does not happen; the next green build carries the branch forward). The flip side of
 * that idiom is the documented hazard: a path mismatch with cd's {@code
 * /cd/api/events/build-succeeded} raises no error anywhere and deployments simply stop, which is
 * why both repos pin the literal and both suites assert their own absolute address.
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

  // No token accompanies the POST: cd's intake is not on the gateway's token-free allowlist (this
  // notifier dials it directly on qits-net, where callers are trusted), so unlike the git host's
  // X-CI-Token there is no guard on the other end asking for one. If cd's intake is ever
  // allowlisted at the gateway, the guard and a token here land in the same change.
  @ConfigProperty(name = "qits.cd.intake-url")
  String intakeUrl;

  @Inject ObjectMapper objectMapper;

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
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(intakeUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
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
