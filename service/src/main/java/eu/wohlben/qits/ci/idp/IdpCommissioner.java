package eu.wohlben.qits.ci.idp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The qits-idp commissioning client: one short-lived oidc client per CI run, minted on demand and
 * deleted when the run closes.
 *
 * <p><b>What replaced what.</b> A publishing step used to push with {@code
 * qits.ci.registry-auth.client-id}/{@code …client-secret} — one static credential, shared by every
 * run of every repository, living in a deployment's environment for as long as the deployment did.
 * qits-idp grew a commissioning API, so the credential a step holds is now this run's own: it exists
 * for the length of one pipeline, it is readable only by containers of that pipeline, and a leak
 * costs what one run could have done rather than what every run could.
 *
 * <p><b>Hand-rolled {@code java.net.http}, like every other client in this deployable.</b> The rule
 * is {@code AGENTS.md}'s native-image one — prefer what the image already has over a REST client
 * library — and the reading side is {@code readTree} plus a walk, so nothing here needs a reflection
 * registration either. The {@code HttpClient} is an instance field rather than a static one, for the
 * reason {@code HttpGitHostRepoListing} writes down: a static one is created at image-build time and
 * native-image refuses the heap it lands in.
 *
 * <p><b>The address is derived, never configured.</b> {@code quarkus.oidc-client.auth-server-url} is
 * already the idp base this service asks for its own machine token, and {@code /api/clients} is the
 * commissioning surface under it. A second key would be a second thing to keep in step with the
 * first, and a deployment that pointed the two at different idps would mint credentials one issuer
 * knows and present tokens another signed.
 *
 * <p><b>The caller is the service's own oidc client, and only a real one may commission.</b> The
 * request carries HTTP Basic of {@code quarkus.oidc-client.client-id} and {@code
 * quarkus.oidc-client.credentials.secret}; qits-idp answers 401 to an unknown pair and 403 when a
 * commissioned client tries to commission again, which is what keeps the tree one level deep.
 *
 * <p><b>{@link #enabled()} is the fallback arm.</b> With {@code
 * quarkus.oidc-client.client-enabled} off — the shipped posture, and every test's — there is no
 * credential to present and nothing to commission with, so this class does nothing at all and a
 * step container's environment is byte-identical to what it was before any of this existed.
 */
@ApplicationScoped
public class IdpCommissioner {

  private static final Logger LOG = Logger.getLogger(IdpCommissioner.class);

  /** What a commission made here is <b>about</b>: one CI run, named by its run id. */
  public static final String CONTEXT_KIND = "ci-run";

  /** Bound on opening the socket, the same 2s every hand-rolled client in this repo carries. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on one whole exchange. Short: the caller is a run worker holding a build slot. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /**
   * How long a commission waits between two attempts at the same call. Five seconds, the same pause
   * {@code CiDaemonLauncher.launch} holds an idp cutover through and for the same reason: what is
   * being waited out is a window of tens of seconds, so a shorter pause only spends the run worker
   * on refusals nobody has fixed yet.
   */
  static final Duration RETRY_PAUSE = Duration.ofSeconds(5);

  /** One commissioned pair. The secret is returned once, by qits-idp, and is never re-readable. */
  public record Commission(String clientId, String secret) {}

  /** One live commission of this owner's, as the listing reports it — no secret, ever. */
  public record LiveClient(String clientId, String contextKind, String contextId) {}

  /**
   * A commission that could not be made, after every attempt the patience window allowed.
   *
   * <p><b>It fails the step, and that is the deliberate posture.</b> Launching credential-less would
   * turn an idp blip into a push 401 deep inside somebody's build, minutes later, with nothing in
   * the record naming the cause. The message names the call that failed instead.
   */
  public static class CommissionFailedException extends RuntimeException {
    public CommissionFailedException(String message) {
      super(message);
    }
  }

  /**
   * The single switch, read from the extension's own key rather than shadowed by one of ours — the
   * same arrangement {@code containers/ContainersClientProducer} makes for the token it fetches.
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean clientEnabled;

  @ConfigProperty(name = "quarkus.oidc-client.auth-server-url")
  String authServerUrl;

  @ConfigProperty(name = "quarkus.oidc-client.client-id")
  String clientId;

  /** Unset on every deployment that has not turned the oidc client on — see {@link #enabled()}. */
  @ConfigProperty(name = "quarkus.oidc-client.credentials.secret")
  Optional<String> clientSecret;

  @ConfigProperty(name = "qits.ci.commission.patience")
  Duration patience;

  @Inject ObjectMapper objectMapper;

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /**
   * Whether this process can commission at all: the oidc client is on and both halves of its own
   * credential are there. Everything else answers "commission nothing, inject nothing".
   */
  public boolean enabled() {
    return clientEnabled && !value(clientId).isBlank() && !secret().isBlank();
  }

  /**
   * Commission one credential for a context, holding through the answers that are about the moment.
   *
   * <p><b>The classification is {@code CiDaemonLauncher.holdThrough}'s, applied to a different
   * hop.</b> Nothing answered, a 5xx and a 401 are held through — the last one because an idp that
   * has just been replaced answers exactly that to a credential that was valid a minute ago, which
   * is the 2026-08-12 lesson this platform already paid for once. A 403 and a 400 are statements
   * about the request that no window fixes, so they are one attempt.
   *
   * @throws CommissionFailedException when every attempt inside the patience window failed
   */
  public Commission commission(String contextKind, String contextId) {
    String url = clientsUrl();
    Instant giveUpAt = Instant.now().plus(patience);
    // Never pause past the window itself — the launcher's rule, for the same reason: a pause longer
    // than the patience makes a short patience mean one attempt while looking like a window.
    Duration pause = RETRY_PAUSE.compareTo(patience) > 0 ? patience : RETRY_PAUSE;
    int attempts = 0;
    String detail;
    while (true) {
      attempts++;
      Attempt attempt = attemptCommission(url, contextKind, contextId);
      if (attempt.commission() != null) {
        LOG.debugf("Commissioned %s for %s %s", attempt.commission().clientId(), contextKind, contextId);
        return attempt.commission();
      }
      detail = attempt.detail();
      if (!attempt.retryable() || !Instant.now().isBefore(giveUpAt) || !sleep(pause)) {
        break;
      }
      LOG.infof(
          "Attempt %d to commission a credential for %s %s did not land (%s) — asking again",
          attempts, contextKind, contextId, detail);
    }
    throw new CommissionFailedException(
        "could not commission a per-run credential (POST "
            + url
            + ", contextKind="
            + contextKind
            + " contextId="
            + contextId
            + ") after "
            + attempts
            + " attempt(s): "
            + detail);
  }

  /** One attempt: the pair, or why not and whether asking again could change the answer. */
  private record Attempt(Commission commission, boolean retryable, String detail) {}

  private Attempt attemptCommission(String url, String contextKind, String contextId) {
    String body =
        "{\"contextKind\":\"" + escape(contextKind) + "\",\"contextId\":\"" + escape(contextId) + "\"}";
    HttpResponse<String> response;
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", basic())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Attempt(null, false, "interrupted while asking qits-idp");
    } catch (Exception unreachable) {
      return new Attempt(null, true, "qits-idp unreachable: " + unreachable);
    }
    int status = response.statusCode();
    if (status == 200 || status == 201) {
      Commission minted = readCommission(response.body());
      return minted == null
          ? new Attempt(null, false, "qits-idp answered " + status + " with no clientId and secret")
          : new Attempt(minted, false, null);
    }
    // 401 is the idp-cutover window; a 5xx is the service's own trouble. Everything else — 403 from
    // a client that may not commission, a 400 on a value — is about the request and stands.
    boolean retryable = status == 401 || status >= 500;
    return new Attempt(null, retryable, "qits-idp answered " + status + ": " + errorOf(response.body()));
  }

  /**
   * Give the credential back. <b>Best effort, always</b>: the caller is a run that is over, and a
   * commission this call could not delete is one the next reconciliation reaps.
   */
  public void decommission(String commissionedClientId) {
    if (!enabled() || value(commissionedClientId).isBlank()) {
      return;
    }
    String url = clientsUrl() + "/" + URLEncoder.encode(commissionedClientId, StandardCharsets.UTF_8);
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", basic())
              .DELETE()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      // 404 is "unknown, or not yours", which for a delete is the outcome that was asked for.
      if (status == 204 || status == 200 || status == 404) {
        LOG.debugf("Decommissioned %s", commissionedClientId);
        return;
      }
      LOG.warnf(
          "Could not decommission %s (DELETE %s): HTTP %d %s — leaving it to the next"
              + " reconciliation",
          commissionedClientId, url, status, errorOf(response.body()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      LOG.warnf(
          "Could not decommission %s (DELETE %s): %s — leaving it to the next reconciliation",
          commissionedClientId, url, e.toString());
    }
  }

  /**
   * Every commission this owner still has live, or empty when the listing could not be read.
   *
   * <p>The two are told apart on purpose: an empty <em>list</em> means qits-idp holds none of this
   * owner's clients, and an empty <em>Optional</em> means nothing was learned — and a reconciliation
   * that read the second as the first would delete nothing, which is right, rather than everything,
   * which is what the opposite confusion would cost.
   */
  public Optional<List<LiveClient>> live() {
    if (!enabled()) {
      return Optional.empty();
    }
    String url = clientsUrl();
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("Authorization", basic())
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warnf(
            "Could not list this service's commissioned clients (GET %s): HTTP %d %s",
            url, response.statusCode(), errorOf(response.body()));
        return Optional.empty();
      }
      return readListing(response.body());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      LOG.warnf("Could not list this service's commissioned clients (GET %s): %s", url, e.toString());
      return Optional.empty();
    }
  }

  /** {@code <auth-server-url>/api/clients} — the base is the one the token comes from. */
  String clientsUrl() {
    return value(authServerUrl).replaceAll("/+$", "") + "/api/clients";
  }

  private Commission readCommission(String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      String id = text(root, "clientId");
      String minted = text(root, "secret");
      return id.isBlank() || minted.isBlank() ? null : new Commission(id, minted);
    } catch (Exception notJson) {
      return null;
    }
  }

  private Optional<List<LiveClient>> readListing(String body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (Exception notJson) {
      LOG.warnf("The commissioned-client listing is not JSON: %s", notJson.toString());
      return Optional.empty();
    }
    if (root == null || !root.isArray()) {
      LOG.warnf("The commissioned-client listing is not a JSON array");
      return Optional.empty();
    }
    List<LiveClient> clients = new ArrayList<>();
    for (JsonNode entry : root) {
      String id = text(entry, "clientId");
      if (id.isBlank()) {
        continue;
      }
      clients.add(new LiveClient(id, text(entry, "contextKind"), text(entry, "contextId")));
    }
    return Optional.of(clients);
  }

  /** The error envelope qits-idp answers a refusal with, flattened into one loggable sentence. */
  private String errorOf(String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      String error = text(root, "error");
      String description = text(root, "error_description");
      if (error.isBlank() && description.isBlank()) {
        return "";
      }
      return description.isBlank() ? error : error + " (" + description + ")";
    } catch (Exception notJson) {
      return "";
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || !value.isTextual() ? "" : value.asText();
  }

  private String basic() {
    String pair = value(clientId) + ":" + secret();
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  private String secret() {
    return clientSecret == null ? "" : value(clientSecret.orElse("")).trim();
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }

  /** Two characters JSON needs escaped; a context id is an id, and this is the belt anyway. */
  private static String escape(String text) {
    return value(text).replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** Wait, or report that this thread is being asked to stop — in which case the retry is over. */
  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
