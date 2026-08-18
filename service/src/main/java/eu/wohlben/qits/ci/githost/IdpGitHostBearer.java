package eu.wohlben.qits.ci.githost;

import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * qits-ci's separately audience-bound credential for reads from qits-githost.
 *
 * <p>The default OIDC client is qits-containers' and its token names that audience, so it must never
 * be reused here. Nothing this class cannot answer stops a read: an empty answer costs the {@code
 * Authorization} header, and the git host refuses the bare request itself — see {@code
 * HttpGitConfigSource#get}.
 */
@ApplicationScoped
public class IdpGitHostBearer implements GitHostBearer {

  private static final Logger LOG = Logger.getLogger(IdpGitHostBearer.class);
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.githost.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("githost") OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  @Override
  public Optional<String> token() {
    if (!enabled) {
      LOG.debug("qits-githost credentials are disabled; the git host reads go out bare");
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-githost: %s", e.toString());
      return Optional.empty();
    }
  }
}
