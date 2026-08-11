package eu.wohlben.qits.ci.containers;

import eu.wohlben.qits.containers.client.ContainersClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The one {@link ContainersClient} this process has, and where its bearer comes from.
 *
 * <p><b>The jar brings no bean of its own, on purpose.</b> {@code ContainersClient} is a plain
 * class that takes its values in a constructor and depends on neither quarkus-arc nor any OIDC
 * extension — which is what lets a daemon with no CDI construct one with {@code new}, and what
 * leaves this decision here. The defaults ship at ordinal 100 in the client jar, so the lines below
 * name keys rather than values.
 *
 * <p><b>{@code @Singleton}, so one {@code HttpClient} serves the process.</b> That object is what an
 * {@code HttpClient} is for, and it must not be static inside the jar: a static one is created at
 * image build time and native-image refuses the heap it lands in.
 *
 * <p><b>The token is asked for per request and it costs the header, never the call.</b> That is the
 * {@code TokenSource} contract: a source that throws is a source that returned nothing, so a broken
 * or unreachable qits-idp turns into a 401 from qits-containers — reportable, one of the four
 * answers — rather than an exception on the run worker. Empty is the shipped posture: {@code
 * quarkus.oidc-client.client-enabled} is {@code false}, the service's own gate {@code
 * qits.auth.machine.required} is {@code false} with it, and the owner in the path is trusted on
 * network trust exactly as every sibling hop is today.
 *
 * <p><b>The wait is bounded, and that is this package's standing rule rather than a preference.</b>
 * The caller is the single-threaded run worker between one pipeline step and the next, so an untimed
 * {@code await().indefinitely()} — which is what every {@code …AndAwait} spelling hides — would park
 * every build on this instance behind one slow token endpoint. {@link TokensHelper} is what makes it
 * one fetch rather than one per call: it holds the token until it expires and refreshes it in the
 * background, so a restarted idp pauses new issuance and nothing else. It is the same helper {@code
 * notify/CdBearer} used, which is this repository's own precedent for the pattern; what is new is
 * the deadline, because that caller chained a {@code Uni} and this seam answers an {@code Optional}.
 */
@ApplicationScoped
public class ContainersClientProducer {

  private static final Logger LOG = Logger.getLogger(ContainersClientProducer.class);

  /**
   * How long a token fetch may take before the call goes out unauthenticated. Short: the helper
   * refreshes ahead of expiry, so reaching this at all means the issuer is in trouble, and a bounded
   * 401 is a better answer for a run worker than an unbounded wait.
   */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "qits.containers.url")
  String url;

  @ConfigProperty(name = "qits.containers.client.request-timeout")
  Duration requestTimeout;

  @ConfigProperty(name = "qits.containers.client.ensure-timeout")
  Duration ensureTimeout;

  /**
   * The single switch, read from the extension's own key rather than shadowed by one of ours: the
   * same value decides whether quarkus-oidc-client builds a real client and whether this class asks
   * it for anything. Deliberately required — a deployment that deletes the shipped line fails to
   * start instead of quietly dropping the credential off every outbound call.
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean tokensEnabled;

  @Inject OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  @Produces
  @Singleton
  ContainersClient containersClient() {
    return new ContainersClient(url, requestTimeout, ensureTimeout, this::bearer);
  }

  /** The access token to put on the next request, or none. Never throws — see the class javadoc. */
  Optional<String> bearer() {
    if (!tokensEnabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(token -> !token.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-containers, calling bare: %s", e.toString());
      return Optional.empty();
    }
  }
}
