package eu.wohlben.qits.ci.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.githost.FakeGitHostRepoListing;
import eu.wohlben.qits.ci.projects.FakeProjectsRepoListing;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The write surface with the machine gate ON — the deployment posture once qits-idp exists.
 *
 * <p><b>There are two guarded writes, and the second one is the interesting shape.</b> This file used
 * to cover the push intake, guarded with the pushed repository, and the manual trigger, guarded with
 * every project. The intake is gone — a push arrives as {@code SCMPublishCommit} off the event log,
 * where a bearer would mean nothing, since what authenticates an event is the bus that carried it
 * rather than a header on a request nobody makes. The cases that asked "may this token push to this
 * repository" have no endpoint left to ask it of.
 *
 * <p>What joined the trigger is {@code POST /ci/api/runs/cancellations}, qits-projects withdrawing a
 * release request's CI. <b>It has two real callers and both are asserted here</b>: a machine caller
 * is judged on its token exactly as the trigger's is, and an operator arrives on the edge's
 * forwarded {@code X-Qits-User}/{@code X-Qits-Roles} session carrying no token at all and is judged
 * by the class-level roles. That is why the {@code MachineAuth} call sits on the machine arm rather
 * than over the whole method, and why a case here asserts the forwarded session is <em>not</em>
 * answered 401 — a guard tightened to "always a token" would break the person, and one dropped
 * entirely would unguard the peer.
 *
 * <p><b>"Exactly as the trigger's is" is new, and it is the 2026-09-05 fix.</b> The cancellation
 * demanded {@code project=*} and its only real sender could not present one: qits-projects' bearer
 * for this hop is the {@code <env>-qits-projects} client's, minted with {@code qits:system}, {@code
 * qits-platform:system} and no structured claims at all, so every superseded release request's
 * cancellation was answered 403 and swallowed at debug by a hop that is best-effort on purpose.
 * {@link #theReleaseRequestCancellationAdmitsQitsProjectsOwnBearer} is that caller's exact shape and
 * is the case that must never go back to 403; the two beside it keep the widening where the ruling
 * put it.
 *
 * <p>{@code POST /ci/api/runs/{runId}/retry} is beside the cancel, in the other category: a person's
 * write, {@code qits:admin} only, and a machine granted every project is refused.
 *
 * <p>The rule they enforced is unchanged, and is why this file stays: a NEW write method that simply
 * omits {@code machineAuth.require*} ships unguarded and nothing says so. Add a write endpoint, add
 * its case here, and keep every address absolute — a moved prefix then shows up as a 404 rather than
 * as a pass.
 *
 * <p>The identity is installed by {@code @TestSecurity} rather than signed by a running idp on
 * purpose. What is under test is this service's decision about a token's claims; whether a signature
 * or an expiry is checked is quarkus-oidc's contract, tested where it lives. A test issuer here
 * would only re-assert the extension.
 *
 * <p><b>The reads are no longer open, and three doors now shut in order.</b> No token at all is a
 * 401. A token granted no roles is a 403 at {@code @RolesAllowed} — qits-platform-idp copies a
 * client's {@code roles} into the token's {@code groups} claim and quarkus-oidc reads that claim as
 * roles with no configuration at all, so a token minted without it authenticates and covers
 * nothing. Only then is {@code MachineAuth} asked, and a wrong audience or an uncovered project is
 * its own 403. Which caller each read serves is spelled out per case: the daemon pin takes the two
 * system roles a machine peer holds, and the run and repository reads take <b>the pair</b> —
 * {@code qits:system} is the machine role, {@code qits:admin} the human one, and both of those
 * callers legitimately read. What mutates is not widened with them: cancelling a run stays
 * {@code qits:admin} alone.
 */
@QuarkusTest
@TestProfile(MachineGuardTest.GateOn.class)
class MachineGuardTest {

  /** The audience this service's machine guard expects — its config default, injected in prod. */
  private static final String OWN_AUDIENCE = "qits-ci";

  /** A valid platform audience that is not ours; the guard must refuse it. */
  private static final String FOREIGN_AUDIENCE = "prod-qits-deployments";

  /**
   * The calling client every case below installs — qits-artifacts, which reads the daemon pin from
   * qits-net. It is a subject, never an audience, and no case asserts it: what is under test is the
   * token's claims, and the caller only has to be some machine.
   *
   * <p>Spelled here rather than taken from {@code QitsClaims}, like the two audiences above and for
   * the same reason. It used to be {@code QitsClaims.ARTIFACTS}, the last service id that library
   * held; the byte-plane split deleted it, because every service is an environment service now and
   * an id carries its environment — {@code <env>-qits-artifacts} — which no constant can know.
   */
  private static final String ARTIFACTS = "prod-qits-artifacts";

  /**
   * The one caller whose subject <em>is</em> the point: qits-projects, the only sender of the
   * cancellation route. Named so that the case asserting its exact token shape reads as the caller
   * it is rather than as one more machine.
   */
  private static final String PROJECTS = "dev-qits-projects";

  /**
   * The gate on, and the {@code %test} dev user off.
   *
   * <p>Blanking the dev user is not a convenience — it is what makes the suite match a deployment.
   * {@code ForwardAuthMechanism} answers every request that carries no {@code X-Qits-User} with the
   * synthetic {@code dev} identity, so under {@code %test} it authenticates first and no other
   * mechanism is ever asked. A real machine call has no such header and no such fallback: forward
   * auth abstains and the bearer is what the request is judged on. Left set, every test below sees a
   * user rather than a machine and passes only by answering 401 for the wrong reason.
   */
  public static class GateOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(MachineAuth.REQUIRED_KEY, "true", "qits.auth.forward.dev-user", "");
    }
  }

  /**
   * The machine roles qits-platform-idp grants a platform service client, copied into the token's
   * {@code groups} claim from {@code qits.idp.client.<id>.roles}. quarkus-oidc reads that claim as
   * the identity's roles with no configuration at all, which is what lets a machine caller satisfy
   * the {@code @RolesAllowed("qits:system")} the guarded controllers carry.
   *
   * <p><b>{@code @TestSecurity} replaces the identity wholesale rather than adding to it</b>, so a
   * case that names only the user installs a caller granted nothing: it authenticates and is then
   * refused 403, which is the shape {@link #aTokenGrantedNoRolesIs403} asserts on purpose. An
   * annotation takes only constant expressions, so the pair below is spelled out at every use.
   */
  private static final String SYSTEM = "qits:system";

  /** The platform-wide half of the same grant, held by the same clients. */
  private static final String PLATFORM_SYSTEM = "qits-platform:system";

  /** A person's role, which the edge forwards in {@code X-Qits-Roles} and no machine token holds. */
  private static final String ADMIN = "qits:admin";

  /** Absolute, like every address here: it is what catches a prefix or a rename regression. */
  private static final String TRIGGER = "/ci/api/events/trigger";

  private static final String TRIGGER_BODY =
      """
      {"name":"SoftwareRelease","payload":{"repository":"guarded-repo","version":"1.0.0"}}""";

  /** The other guarded write: qits-projects withdrawing a release request's CI. Absolute too. */
  private static final String CANCELLATIONS = "/ci/api/runs/cancellations";

  /** The catalogue, so a case can say whether this instance holds a repository at all. */
  @Inject FakeGitHostRepoListing gitHostListing;

  /**
   * The other catalogue — the only one that answers a repository's <em>project</em>, so it is what a
   * project-scoped caller can be judged against at all. Unconfigured by default, which is what every
   * other case here runs on.
   */
  @Inject FakeProjectsRepoListing projectsListing;

  private static final String CANCELLATIONS_BODY =
      """
      {"repoId":"guarded-repo","releaseRequestId":"rr-guarded"}""";

  @Test
  void theManualTriggerWithNoMachineTokenIs401() {
    // 401, not 403: nothing was presented, so the answer is "present something". The forward-auth
    // dev user is blanked in this profile and would make no difference anyway — a user is not a
    // machine.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theManualTriggerNeedsATokenGrantedEveryProject() {
    // 503 is the guard PASSING here, and it is deterministic in this profile: the trigger evaluates
    // before it answers, this instance has no git host (qits.ci.git-host-url answers on nothing), so
    // no candidate repository can be read and the endpoint says so rather than inventing a 2xx. What
    // a dropped or tightened guard looks like is 401 or 403, which is what this case rules out.
    // The endpoint's own contract is CiManualTriggerTest's.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(503);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = FOREIGN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aTokenMintedForAnotherServiceIs403() {
    // Granted everything, and addressed elsewhere. The audience is the half of the guard that says
    // "this token is for me"; a platform where it were optional would let any service's token act
    // here.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aTokenWithNoProjectClaimAndNoPlatformRoleIs403() {
    // It names nothing to evaluate: no project claim, and no platform-tier role either. The
    // ordinary machine role is what every read here takes and is not a statement about scope.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aPlatformTierTokenWithNoProjectClaimEvaluatesEverything() {
    // The arm the live 403 landed on, and the ruling behind it is in CiEventController.scopeOf:
    // qits-idp mints its agent and operator credentials with NO structured claims at all — measured
    // on a commissioned workspace client, which pushes protected refs at qits-githost on its roles
    // alone — so demanding one here made this the only door in the service no real machine caller
    // could open. 503 is the guard passing; the case above is what keeps the widening to callers
    // that already hold a platform-wide credential.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(503);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "project-alpha")
      })
  void aProjectScopedTokenIsAdmittedAndEvaluatedAgainstItsOwnProject() {
    // The 2026-09-04 fix. This used to be a 403 on the reading that an event names no repository, so
    // the only honest grant was project=*; a candidate carries its project now, so the token is
    // admitted and the evaluation is narrowed to it instead. 503 is the guard passing, for the same
    // reason the project=* case above answers it: nothing here can be read. What a refused token
    // looks like is the case below.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(503);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "project-beta")
      })
  void aTokenWhoseProjectHoldsNoRepositoryHereIs403() {
    // One candidate exists and it is not this token's — the cross-project case, at the door. 403
    // rather than an empty 200: the catalogue is not empty, so "matched nothing" would be a claim
    // about the event when the truth is that the token covers nothing here. The listing hands out no
    // project for this candidate, which is the unprovable case and lands the same way: unprovable is
    // not "yours". Which repositories a scoped call really evaluates is the ci module's suite, where
    // a catalogue with projects in it can be staged.
    gitHostListing.set("some-other-projects-repo");
    try {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body(TRIGGER_BODY)
          .when()
          .post(TRIGGER)
          .then()
          .statusCode(403);
    } finally {
      gitHostListing.set();
    }
  }

  @Test
  void aForwardedAdminSessionTriggersByHand() {
    // The trigger's other real caller, and why the machine check sits on the machine arm here too: a
    // person invoking this on purpose is what the operation is for, and the edge's forwarded session
    // carries no bearer at all. 503 is the guard passing; a guard demanding a token unconditionally
    // answers this 401, and one left at the class's machine-only role answers it 403.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", ADMIN)
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(503);
  }

  @Test
  @TestSecurity(user = ARTIFACTS)
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aTokenGrantedNoRolesIs403() {
    // A client id qits-platform-idp knows with no `.roles` line beside it mints exactly this:
    // correctly signed, addressed here, granted every project, and carrying an empty `groups`
    // claim. It authenticates and covers nothing, because @RolesAllowed shuts before MachineAuth is
    // ever asked. A 403 rather than the 401 an absent token gets, which is what tells a missing idp
    // grant from a missing sender.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(TRIGGER_BODY)
        .when()
        .post(TRIGGER)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theDaemonPinIsAMachinePeersReadRatherThanAnOpenOne() {
    // qits-platform-artifacts' collector reads the pin before it deletes a daemon binary. It holds
    // this service's audience and the two system roles, so the read answers it — and the endpoint
    // is closed to everyone else, which is the contract rather than an oversight.
    given().when().get("/ci/api/daemon").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theRepositoryListingIsReadByMachinesToo() {
    // It used to answer a machine 403, on the reading that qits-spa-ci draws it so it is a person's.
    // That was the human role standing in for "may read", and the price was a machine peer being
    // told to hold qits:admin. A read takes the pair.
    given().when().get("/ci/api/repositories").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aMachinePeerPollsOneRunWithItsOwnRole() {
    // qits-platform-maintenance asks for a bump and then polls GET /ci/api/runs/{id} until it is
    // terminal. 404 is the guard PASSING — no such run in this instance — and it is what rules out
    // the 401 and the 403 this case exists for.
    given().when().get("/ci/api/runs/no-such-run").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theRunListingsAreReadByMachinesToo() {
    // The other three reads on the same resource, so that a widening applied to one of them and not
    // the others shows up here rather than in a poller that half works.
    given().when().get("/ci/api/runs/active").then().statusCode(200);
    given().when().get("/ci/api/runs/finished").then().statusCode(200);
    given().when().get("/ci/api/runs?repositoryId=guarded-repo").then().statusCode(200);
    given().when().get("/ci/api/repositories/summary").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void cancellingARunStaysAPersonsAndRefusesAMachine() {
    // The one write on the read resource, and the whole reason the reads' widening is method-scoped
    // rather than class-wide: stopping somebody's build is not a thing a peer service does.
    given().when().post("/ci/api/runs/no-such-run/cancel").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void retryingARunStaysAPersonsAndRefusesAMachine() {
    // The second write on the read resource, and it takes the same method-level qits:admin the
    // cancel does for the same reason: starting somebody's build is not a thing a peer service does
    // either. A machine holding every project is still 403 here.
    given().when().post("/ci/api/runs/no-such-run/retry").then().statusCode(403);
  }

  @Test
  void theReleaseRequestCancellationWithNoMachineTokenIs401() {
    // The one write here a machine really does perform — qits-projects withdrawing a release
    // request's CI. Nothing presented, and the %test dev user is blanked in this profile, so the
    // answer is "present something".
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(CANCELLATIONS_BODY)
        .when()
        .post(CANCELLATIONS)
        .then()
        .statusCode(401);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void theReleaseRequestCancellationNeedsATokenGrantedEveryProject() {
    // 202 is the guard PASSING, and it is deterministic: this instance holds no run for that
    // request, and "nothing left in flight" is the state the caller asked for rather than a 404.
    // What a dropped or tightened guard looks like is 401 or 403, which is what this case rules out.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(CANCELLATIONS_BODY)
        .when()
        .post(CANCELLATIONS)
        .then()
        .statusCode(202);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = FOREIGN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "*")
      })
  void aTokenMintedForAnotherServiceMayNotCancelAReleaseRequestsRuns() {
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(CANCELLATIONS_BODY)
        .when()
        .post(CANCELLATIONS)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = PROJECTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void theReleaseRequestCancellationAdmitsQitsProjectsOwnBearer() {
    // THE case. This is the shape of the only sender this route has: the <env>-qits-projects client
    // credential, groups of qits:system + qits-platform:system (+ its own clients/<id>, which
    // nothing here reads), and NO structured claims — qits-idp's bootstrap grants a project claim to
    // exactly two clients and this is neither. It was a deterministic 403 against project=*, on
    // every supersession, logged at debug by a sender that treats this hop as best-effort. 202 is
    // the guard passing; a regression to 403 puts the cancellation feature back to inert.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(CANCELLATIONS_BODY)
        .when()
        .post(CANCELLATIONS)
        .then()
        .statusCode(202);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM})
  @OidcSecurity(claims = {@Claim(key = "aud", value = OWN_AUDIENCE)})
  void aCancellationTokenWithNoProjectClaimAndNoPlatformRoleIs403() {
    // The other side of the case above, and what keeps the widening where CiEventController.scopeOf
    // put it: an ordinary qits:system client that carries no claim names nothing to cancel in. The
    // platform-tier role is a grant somebody wrote down; an absent claim is not one.
    given()
        .contentType(MediaType.APPLICATION_JSON)
        .body(CANCELLATIONS_BODY)
        .when()
        .post(CANCELLATIONS)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "project-alpha")
      })
  void aProjectScopedTokenCancelsInItsOwnProject() {
    // The arm this route did NOT have, and the old comment said why: it names a repoId whose project
    // qits-ci would have to look up to judge. The lookup is the catalogue that already decides the
    // trigger's scope, so the two doors read a project claim the same way. Staged here rather than
    // assumed: the listing has to place the repository in the token's project for this to be a 202.
    projectsListing.set(new CiRepoRef("guarded-repo", "project-alpha", "guarded"));
    try {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body(CANCELLATIONS_BODY)
          .when()
          .post(CANCELLATIONS)
          .then()
          .statusCode(202);
    } finally {
      projectsListing.unset();
    }
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "project-beta")
      })
  void aProjectScopedTokenMayNotCancelInAnotherProject() {
    // The same catalogue, the same repository, another project on the token — the cross-tenant case,
    // at the door. 403 rather than an empty 202: "nothing was in flight" is a statement about the
    // release request, and saying it to a caller that covers this repository for nobody would be a
    // lie in the caller's favour.
    projectsListing.set(new CiRepoRef("guarded-repo", "project-alpha", "guarded"));
    try {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body(CANCELLATIONS_BODY)
          .when()
          .post(CANCELLATIONS)
          .then()
          .statusCode(403);
    } finally {
      projectsListing.unset();
    }
  }

  @Test
  @TestSecurity(user = ARTIFACTS, roles = {SYSTEM, PLATFORM_SYSTEM})
  @OidcSecurity(
      claims = {
        @Claim(key = "aud", value = OWN_AUDIENCE),
        @Claim(key = QitsClaims.PROJECT, value = "project-alpha")
      })
  void aProjectScopedTokenIsRefusedWhenTheCatalogueCannotPlaceTheRepository() {
    // The fail-closed half, and it is deliberate rather than incidental: with no qits-projects
    // configured the git host's listing answers storage ids and no projects at all, so this instance
    // cannot prove the repository is the token's. Unprovable is not "yours". The callers that must
    // survive a listing outage are the unscoped ones, and they never reach the lookup.
    gitHostListing.set("guarded-repo");
    try {
      given()
          .contentType(MediaType.APPLICATION_JSON)
          .body(CANCELLATIONS_BODY)
          .when()
          .post(CANCELLATIONS)
          .then()
          .statusCode(403);
    } finally {
      gitHostListing.set();
    }
  }

  @Test
  void aForwardedAdminSessionCancelsAReleaseRequestsRuns() {
    // The other caller of the same route, and why the MachineAuth check sits on the machine arm
    // rather than over the whole method: an operator arrives on the edge's forwarded session, which
    // carries no bearer at all and is judged by the class-level roles. A guard that demanded a
    // machine token unconditionally would answer this 401.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", ADMIN)
        .contentType(MediaType.APPLICATION_JSON)
        .body(CANCELLATIONS_BODY)
        .when()
        .post(CANCELLATIONS)
        .then()
        .statusCode(202);
  }

  @Test
  void aForwardedAdminSessionReadsTheRepositoryListing() {
    // The other side of the same route, and the reason no @TestSecurity is here: the headers ARE
    // the contract. The edge establishes the session and qits-gateway asserts the pair, which is
    // what this profile's blanked dev user makes visible.
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", ADMIN)
        .when()
        .get("/ci/api/repositories")
        .then()
        .statusCode(200);
  }
}
