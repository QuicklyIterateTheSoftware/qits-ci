package eu.wohlben.qits.ci.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two identity tracks qits-ci accepts, one helper each — because a story that presented the
 * wrong one would be documenting a door that does not exist.
 *
 * <h2>A machine is a bearer</h2>
 *
 * <p>{@link #platformService(RequestSpecification)} presents an RS256 token minted by {@link
 * MockIdp} against the very JWKS the launched process fetched at startup: {@code aud=qits-ci}
 * (what {@code qits.auth.machine.audience} pins), {@code groups=[qits:system]} — the machine role
 * quarkus-oidc reads as a role with no configuration — and {@code project=*}, which is what {@code
 * POST /ci/api/events/trigger} demands, since an event names no repository and the
 * repository-scoped grant it would otherwise need is "all of them".
 *
 * <h2>A person is a pair of headers</h2>
 *
 * <p>{@link #operator(RequestSpecification)} sends {@code X-Qits-User} and {@code X-Qits-Roles}
 * instead, which is what the platform edge asserts for a logged-in human: this service
 * authenticates no person itself. That is not a shortcut around the bearer — the OIDC tenant is
 * <b>bearer-only</b>, so a request carrying no {@code Authorization} header is never challenged and
 * falls through to the header mechanism, exactly as it does behind the edge. Using it is what makes
 * the read stories say "an operator" honestly rather than dressing a person up as a machine.
 *
 * <p><b>The synthetic {@code dev} identity is not available here and that is the point.</b> The
 * forward-auth mechanism's fallback is {@code %dev}/{@code %test}-scoped <em>and</em> guarded on
 * {@code LaunchMode.NORMAL}; a launched artifact is under neither, so an anonymous request really
 * is anonymous and the roles below are the only thing opening these doors.
 */
public final class StoryIdentities {

  /** The audience this service enforces — a literal, because {@code application.properties} pins it. */
  public static final String AUDIENCE = "qits-ci";

  /** The machine role a platform peer holds, and what {@code CiEventController} requires. */
  public static final String MACHINE_ROLE = "qits:system";

  /** The human role the run listing accepts beside the machine one. */
  public static final String HUMAN_ROLE = "qits:admin";

  /** The header the edge names the logged-in person in. */
  public static final String USER_HEADER = "X-Qits-User";

  /** The header the edge asserts that person's roles in, comma-separated. */
  public static final String ROLES_HEADER = "X-Qits-Roles";

  private StoryIdentities() {}

  /**
   * A platform peer's bearer, wide enough to trigger an evaluation over every repository.
   *
   * <p>Minted fresh per call rather than cached: a token is a credential and a helper that handed
   * the same string to two stories would make {@link
   * eu.wohlben.qits.userflows.report.ReportAssertions#assertNotLeaked} a weaker claim than it reads
   * as.
   */
  public static String platformToken() {
    return MockIdp.attach()
        .token()
        .subject("qits-platform-orchestrator")
        .audience(AUDIENCE)
        .groups(MACHINE_ROLE)
        .claim("project", "*")
        .mint();
  }

  /** {@code given()} with a platform peer's bearer on it. */
  public static RequestSpecification platformService(RequestSpecification request) {
    return request.header("Authorization", "Bearer " + platformToken());
  }

  /** {@code given()} with the two headers the edge asserts for a logged-in operator. */
  public static RequestSpecification operator(RequestSpecification request) {
    return request.header(USER_HEADER, "alice").header(ROLES_HEADER, HUMAN_ROLE);
  }
}
