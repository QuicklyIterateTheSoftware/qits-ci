package eu.wohlben.qits.ci.notify;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The token qits-ci presents when it calls qits-platform-deployments — {@code
 * aud=qits-platform-deployments}, minted by qits-idp against this service's own client credentials.
 * The audience is {@code quarkus.oidc-client.grant-options.client.audience}, and it moves with the
 * intake url the notifier posts to.
 *
 * <p><b>Absent unless a deployment configures it.</b> {@code quarkus.oidc-client.client-enabled} is
 * shipped {@code false}, so the extension builds a disabled client, the process boots with no secret
 * and nothing is ever dialled; {@link #bearer()} answers empty and {@link CdBuildNotifier} sends the
 * request bare, exactly as it did before qits-idp existed. That is the same one-switch shape as the
 * inbound gate, and the two are independent: this service can present a token before it demands one,
 * or the reverse.
 *
 * <p><b>Non-blocking, and cached.</b> The caller is a run worker, which must not
 * park, so this returns a {@code Uni} rather than a token — the fetch runs on the event loop and the
 * POST is chained onto it. {@link TokensHelper} is what makes it one fetch rather than one per green
 * run: it holds the token until it expires and refreshes it in the background, which is why a
 * restarted idp pauses new issuance and nothing else.
 */
@ApplicationScoped
public class CdBearer {

  /**
   * The single switch, read from the extension's own key rather than shadowed by one of ours: the
   * same value decides whether quarkus-oidc-client builds a real client and whether this class asks
   * it for anything. Deliberately required — a deployment that deletes the shipped line fails to
   * start instead of quietly dropping the credential off every outbound call.
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean enabled;

  @Inject OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  CdBearer() {}

  /** For tests and callers outside CDI. */
  CdBearer(boolean enabled, OidcClient oidcClient) {
    this.enabled = enabled;
    this.oidcClient = oidcClient;
  }

  /**
   * The {@code Authorization} value to send, or empty when no client credentials are configured.
   *
   * <p>A failed fetch fails the {@code Uni} rather than yielding empty. The caller then skips the
   * notification, which is the right answer: an unauthenticated POST to a guarded intake would be
   * refused anyway, and silently sending one would turn a credential problem into a mysterious 401
   * in another service's log.
   */
  public Uni<Optional<String>> bearer() {
    if (!enabled) {
      return Uni.createFrom().item(Optional.empty());
    }
    return tokens.getTokens(oidcClient).map(t -> Optional.of("Bearer " + t.getAccessToken()));
  }
}
