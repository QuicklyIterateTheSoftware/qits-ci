package eu.wohlben.qits.ci.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Completes {@link ForwardAuthMechanism}'s trusted request into a {@link SecurityIdentity}: the
 * principal is the header-supplied username, and that is all.
 *
 * <p><b>No roles, deliberately.</b> The monolith's version of this class also read a comma-separated
 * groups header and turned it into roles. That is gone rather than unwired: authorization is a
 * single global check ({@code qits.auth.required-role}) performed at qits-gateway, which therefore
 * emits no groups header — one fewer trusted header, and it keeps "the gateway terminates auth
 * entirely" literally true. No code in this service makes a role decision, so roles here would be a
 * security control that decides nothing.
 *
 * <p>Do not restore them to "match the monolith". If a per-resource role decision is ever needed
 * here, that is a new design (scoped tokens), not this class growing a header back. See
 * migration-auth-plan.md §4.3.
 *
 * <p>The principal is the <b>name</b>, not the stable subject id: the id travels alongside it as
 * {@code X-Qits-User-Id} and nothing reads it yet, while the name is what the platform's existing
 * audit rows hold.
 */
@ApplicationScoped
public class ForwardAuthIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

  @Override
  public Class<TrustedAuthenticationRequest> getRequestType() {
    return TrustedAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
    return Uni.createFrom()
        .item(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
                .build());
  }
}
