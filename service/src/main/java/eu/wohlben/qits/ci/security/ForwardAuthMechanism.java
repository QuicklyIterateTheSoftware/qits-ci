package eu.wohlben.qits.ci.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Turns the {@code X-Qits-User} header qits-gateway asserts into this service's {@link
 * SecurityIdentity}. It is the whole of this service's relationship with authentication.
 *
 * <p><b>This service authenticates nothing.</b> The gateway performs the login, strips every
 * client-supplied {@code X-Qits-*} header from every inbound request, and injects the resulting
 * identity — so the header is believed here unconditionally, which is exactly what makes the
 * stripping load-bearing rather than tidy. There is no variant to choose and no scheme to
 * configure; a service alone is, by construction, unauthenticated, and its safety is the gateway's
 * and the network's property, never its own.
 *
 * <p><b>Anonymous is not a denial.</b> Nothing here carries an authorization policy, so a missing
 * header yields an anonymous identity and the request proceeds. That is the intended behaviour, not
 * an oversight: the identity exists to name the actor, and reaching this service at all already
 * implies you are inside the trusted network. {@link #getChallenge} is present because the
 * interface demands it — no policy in this service can reach it.
 *
 * <p>See migration-auth-plan.md §2 and §12.
 */
@ApplicationScoped
public class ForwardAuthMechanism implements HttpAuthenticationMechanism {

  @ConfigProperty(name = "qits.auth.forward.user-header")
  String userHeader;

  @ConfigProperty(name = "qits.auth.forward.dev-user")
  Optional<String> devUser;

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String user = context.request().getHeader(userHeader);
    if (user == null || user.isBlank()) {
      // The %dev/%test-scoped synthetic identity — no gateway in front of dev mode or the test
      // suite, and this is what keeps this repo's suite runnable with no auth setup at all.
      // LaunchMode-guarded on top of the config scoping: a prod build stays anonymous even if the
      // property leaks in via env.
      if (devUser.isEmpty() || LaunchMode.current() == LaunchMode.NORMAL) {
        return Uni.createFrom().nullItem();
      }
      user = devUser.get();
    }
    // Through the IdentityProviderManager (not building the identity here) so
    // SecurityIdentityAugmentors keep working.
    return identityProviderManager.authenticate(
        HttpSecurityUtils.setRoutingContextAttribute(
            new TrustedAuthenticationRequest(user), context));
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().item(new ChallengeData(401, null, null));
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of(TrustedAuthenticationRequest.class);
  }
}
