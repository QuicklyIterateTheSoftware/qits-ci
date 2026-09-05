package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.ci.error.BadRequestException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * The one place a domain event reaches ci by hand: {@code POST /ci/api/events/trigger}. The write
 * surface of {@code /ci/api} that guards a machine caller.
 *
 * <p><b>The push intake used to live here and there is nothing left of it.</b> {@code POST
 * /ci/api/events/post-receive} was the wire contract between the git host's post-receive hook and
 * ci — {@code {repoId, branch, oldSha, newSha}}, fire and forget. It became {@code
 * bus/ScmPublishCommitListener}, a durable consumption of qits-githost's {@code SCMPublishCommit},
 * and on 2026-09-05 that retired too: <b>the platform runs no CI outside release requests, so an
 * ordinary push triggers nothing.</b> What was an endpoint, a DTO, a machine guard, a listener bean
 * and a replay-by-hand trick is now no code at all.
 *
 * <p>This resource is therefore no longer a pair, and the operation that is left is the one that was
 * <b>not</b> hidden from the OpenAPI document: a person invokes it on purpose and its contract is
 * written down nowhere else. It <b>evaluates before it answers</b>, because the event it carries is
 * on no log and nothing anywhere can offer it a second time — see {@link #trigger}.
 *
 * <h2>What the guard names</h2>
 *
 * <p><b>It demanded {@code project=*} and it does not any more.</b> The argument was sound and its
 * premise expired: an event names no repository, so the call is evaluated against every candidate,
 * so the narrowest honest grant for it was every project — <em>because qits-ci could not name a
 * project</em>. It can. A candidate is a {@code CiRepoRef} carrying {@code (repoId, projectId,
 * name)}, and with {@code qits.ci.projects-url} set the catalogue is qits-projects' own listing, so
 * "which project is this repository in" is a question this service answers on every evaluation
 * already. The line in {@code SoftwareRelease}'s comment — "no projectId, qits-ci never learns one"
 * — is two campaigns old.
 *
 * <p>So the door admits a <b>project-scoped</b> machine caller, and what it grants is exactly what
 * the token says: the evaluation is narrowed to the repositories the catalogue places in that
 * project. A repository of another project is never asked, cannot match and cannot be made to run —
 * <b>refused by construction rather than by a check</b>, which is the shape to prefer for a door
 * whose request names no target. Measured 2026-09-04: a commissioned client holding {@code
 * qits:system} was 403 here while every other {@code /ci/api} read answered the same bearer, which
 * made the documented manual re-fire mechanism unusable by exactly the callers that need it — and
 * the claim that token was refused over turned out to be one qits-idp never minted for it at all,
 * which is the fifth arm of {@link #scopeOf()}.
 *
 * <p>Four things travel with that:
 *
 * <ul>
 *   <li><b>{@code project=*} is unchanged</b> — the whole catalogue, platform pipelines included.
 *   <li><b>A project qits-ci cannot place any repository in is a 403</b>, not an empty 200: the
 *       token covers nothing here, and saying "evaluated, matched none" would read as a statement
 *       about the event. That is also what an unreachable qits-projects listing produces for a
 *       scoped caller, which is the fail-closed direction — a listing that cannot name projects
 *       narrows a scoped call to nothing rather than widening it to everybody.
 *   <li><b>Platform pipelines are not part of a scoped evaluation.</b> One repository's file acting
 *       on the whole catalogue is a platform-wide act, and {@code project=*} is the honest grant for
 *       it.
 *   <li><b>A token with no {@code project} claim is admitted on a platform-tier role</b>, and
 *       refused without one. That is the arm the live 403 actually landed on — the platform's
 *       commissioned credentials carry no structured claims at all — and the argument for it is in
 *       {@link #scopeOf()}, which is where to read before changing any of this.
 * </ul>
 *
 * <p><b>The guard sits on the machine arm, like {@code CiRunController.cancelReleaseRequestRuns}'s
 * does</b>, because this route has two real callers. A machine arrives with a bearer and is judged
 * on its claims; an operator arrives on the edge's forwarded {@code X-Qits-User}/{@code
 * X-Qits-Roles} session, carries no token at all, and is judged by the roles — which is why the
 * method names the pair {@code {qits:admin, qits:system}} the cancellation names, rather than the
 * class's machine-only role. A person invoking this on purpose is what the whole operation is for.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:system")
public class CiEventController {

  private static final Logger LOG = Logger.getLogger(CiEventController.class);

  /** What a caller told to retry should wait. Short: the usual cause is a git host coming back. */
  private static final String RETRY_AFTER_SECONDS = "5";

  @Inject CiEventTriggerService triggers;

  @Inject ObjectMapper objectMapper;

  @Inject MachineAuth machineAuth;

  /**
   * Which caller this is, and nothing more. {@code isAnonymous} is not consulted anywhere here: what
   * the guard turns on is whether a validated machine token is present, which is a different fact
   * from whether the request has a name for an audit row.
   */
  @Inject SecurityIdentity identity;

  /** One domain event, supplied by the caller instead of by the bus. */
  public record TriggerEventRequest(
      @Schema(
              description = "The event signature, matched against a trigger file's `event:`",
              example = "SoftwareRelease")
          String name,
      @Schema(
              description = "The event payload, exactly as it would arrive off the bus",
              type = SchemaType.OBJECT,
              implementation = Object.class)
          JsonNode payload,
      @Schema(description = "ISO-8601 instant. Absent: now.", example = "2026-08-04T09:00:00Z")
          String occurredAt,
      @Schema(description = "UUID. Absent: a fresh random one — see the dedupe note.")
          String eventId) {}

  /**
   * What the evaluation did, which is what the caller waited for.
   *
   * @param eventId the id the evaluation ran under, canonical lowercase
   * @param runIds the runs it recorded. Every id is a row that exists now, findable by
   *     {@code triggerEventId} on {@code GET /ci/api/runs}.
   * @param repositoriesRead how many candidate repositories answered and were evaluated
   * @param repositoriesSkipped the candidates that did not answer — unreachable, gone, no {@code
   *     main}, or not reached before the deadline
   */
  public record TriggerEventResult(
      String eventId, List<String> runIds, int repositoriesRead, List<String> repositoriesSkipped) {}

  /**
   * Evaluates every repository's {@code .config/qits/ci-event-*.yml} against an event the caller
   * supplies, and runs whichever pipelines select it — for any domain-event trigger type, not one
   * kind of them.
   *
   * <p>The bus stays the primary trigger. This is the second inbound adapter of the same seam: it
   * builds a {@link CiEventTriggerService.Arrival} exactly as {@code bus/CiEventTriggerListener}
   * does, so the engine cannot tell the two apart and nothing web-shaped reaches the {@code ci}
   * module.
   *
   * <h2>It evaluates before it answers, and that is the guarantee</h2>
   *
   * <p><b>200 means the evaluation happened.</b> Every id in {@code runIds} is a run row that exists
   * now; an empty list with an empty {@code repositoriesSkipped} means the event was offered to
   * every candidate repository and matched none of them. <b>503 means it did not happen</b> — no
   * candidate could be read, or the evaluation threw — and the caller should retry.
   *
   * <p>This <b>replaced a 202</b>, and the replacement is the fix for a measured loss. The endpoint
   * used to hand the event to {@code ci-trigger-worker}'s bounded queue and answer "accepted"
   * whatever came back. A bus frame can afford that, because a frame that is not evaluated stays
   * owed and the next catch-up sweep offers it again. A caller-supplied event is on no log, holds no
   * claim, and nothing anywhere will offer it a second time — so for this endpoint "queued" and
   * "lost" were the same answer, and on 2026-08-10 a bootstrap's release replay was answered 2xx for
   * an event that was never evaluated, with no line at any level to say so. An event nobody can
   * redeliver must be evaluated by the process that accepts it, or refused.
   *
   * <p>So the call is as long as the evaluation: one git-host read per candidate repository, bounded
   * by {@code qits.ci.trigger-deadline-seconds}. Candidates not reached in time are reported as
   * skipped rather than silently dropped.
   *
   * <h2>The id decides rerun or dedupe, and that is the whole contract</h2>
   *
   * <p>The engine's at-most-once guarantee is the unique constraint {@code (trigger_event_id,
   * repo_id, config_path)}, so an id that has already triggered runs nothing and says nothing about
   * it. Both behaviours are therefore reachable and both are wanted:
   *
   * <ul>
   *   <li><b>Omit {@code eventId}</b> and a fresh random UUID is minted. The evaluation is a
   *       <em>rerun</em>: it collides with nothing, so the same payload may be replayed as often as
   *       you like. This is the default because a rerun is what a person asks for.
   *   <li><b>Pass {@code eventId}</b> and you opt into the dedupe. Repeating the call is then
   *       idempotent — the second one records no run — which is what makes this safe to put in a
   *       bootstrap script that may run twice.
   * </ul>
   *
   * <p>The id comes back in the response body in canonical lowercase form, and it is what a caller
   * matches against {@code triggerEventId} on {@code GET /ci/api/runs} to find what it caused. It is
   * also what makes a retry after a 503 safe: pass one, and a retry that follows an evaluation which
   * did reach some repositories records no second run there.
   *
   * <p>{@code occurredAt} lands on the run row as the event snapshot's timestamp, exactly as a bus
   * arrival's would, and reaches the step containers as {@code $QITS_EVENT_OCCURRED_AT}. The payload
   * is passed through as the bytes the caller sent, minus insignificant whitespace — a payload
   * copied out of the event log is already canonical and stays that way. It is never bound to a
   * type: the engine {@code readTree}s it and walks it, and inventing a canonicalization here would
   * be a second wire format.
   *
   * <p>The guard is the class javadoc's: a machine caller is evaluated against its own project's
   * repositories, {@code project=*} against every one of them, and an operator's forwarded session
   * against every one of them too. A scoped caller's 200 therefore means "every repository of your
   * project was asked", and {@code repositoriesRead} is the count of those.
   */
  @POST
  @Path("/trigger")
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
  @Operation(summary = "Run every event pipeline that selects a caller-supplied domain event")
  @APIResponse(
      responseCode = "200",
      description = "Evaluated, with the runs it recorded and which repositories it could ask",
      content = @Content(schema = @Schema(implementation = TriggerEventResult.class)))
  @APIResponse(
      responseCode = "400",
      description = "Blank name, missing payload, or an unparseable timestamp or id")
  @APIResponse(
      responseCode = "403",
      description =
          "The token covers no repository here — its project claim names a project this instance"
              + " can place no repository in")
  @APIResponse(
      responseCode = "503",
      description = "Not evaluated — no candidate repository could be read. Retry.",
      content = @Content(schema = @Schema(implementation = TriggerEventResult.class)))
  public Response trigger(TriggerEventRequest request) {
    if (request == null || request.name() == null || request.name().isBlank()) {
      throw new BadRequestException("Event name is required");
    }
    if (request.payload() == null || request.payload().isNull()) {
      throw new BadRequestException("Event payload is required");
    }
    if (!request.payload().isObject()) {
      throw new BadRequestException("Event payload must be a JSON object");
    }
    String eventId = eventId(request.eventId());
    Instant occurredAt = occurredAt(request.occurredAt());

    String projectScope = scopeOf();
    CiEventTriggerService.Evaluation done;
    try {
      done =
          triggers.evaluateNow(
              new CiEventTriggerService.Arrival(
                  eventId, request.name(), occurredAt, payloadText(request.payload())),
              projectScope);
    } catch (CiEventTriggerService.NoRepositoriesInProject coversNothing) {
      // A definite answer about the caller, so it is a refusal rather than the 503 below: this
      // instance holds repositories and none of them is that project's. Named ahead of the general
      // catch, which exists for evaluations that did not happen.
      LOG.warnf(
          "Event %s (%s) was refused: the caller's project %s holds no repository this instance can"
              + " name",
          eventId, request.name(), coversNothing.project());
      throw new ForbiddenException("Token project claim covers no repository qits-ci can name");
    } catch (RuntimeException notEvaluated) {
      // The one thing this endpoint may never do is answer 2xx for an event it did not evaluate:
      // nothing will redeliver it. So the caller is told to come back, loudly.
      LOG.errorf(
          notEvaluated, "Event %s (%s) could not be evaluated", eventId, request.name());
      return unavailable(new TriggerEventResult(eventId, List.of(), 0, List.of()));
    }
    TriggerEventResult result =
        new TriggerEventResult(
            eventId, done.runIds(), done.repositoriesRead(), done.repositoriesSkipped());
    if (!done.answered()) {
      LOG.warnf(
          "Event %s (%s) reached no readable repository — answering 503 so the caller retries",
          eventId, request.name());
      return unavailable(result);
    }
    return Response.ok(result).build();
  }

  /**
   * The platform-tier roles, in the two spellings qits-platform-idp grants them.
   *
   * <p>They are what a credential that acts <em>for the platform</em> carries, as against one that
   * acts for a project — the same distinction {@code qits-platform:admin} draws on the deployments
   * read surface. Spelled here rather than taken from {@code QitsClaims}, like every other role on
   * this service's annotations, because a role is a string qits-idp issues and this repository holds
   * no vocabulary for it.
   */
  private static final String PLATFORM_SYSTEM_ROLE = "qits-platform:system";

  private static final String PLATFORM_ADMIN_ROLE = "qits-platform:admin";

  /**
   * The guard, and the whole of it: what this caller may have the event evaluated against.
   *
   * <p>{@code null} is every project; a non-null value narrows the evaluation to it. The order of
   * the questions is the contract and each one has a status code behind it:
   *
   * <ol>
   *   <li><b>Not a machine</b> — the operator's forwarded session, already judged by this method's
   *       roles. Every project, exactly as the cancellation's forwarded arm is trusted.
   *   <li><b>{@link MachineAuth#require()}</b> — 401 with no token once the gate is on, 403 for one
   *       addressed to another service. Presence and audience only; the claim below is this
   *       method's question rather than the library's, because "granted some project" is not an
   *       equality check.
   *   <li><b>{@code project=<p>}</b> — that project, and the narrowing is what admits the caller.
   *   <li><b>{@code project=*}</b> — every project. Read on the token side only, so a caller cannot
   *       widen its own check by naming {@code "*"} as a target: this method never compares it to
   *       one.
   *   <li><b>No {@code project} claim at all</b> — the token is not project-scoped, so the door asks
   *       the other half of what qits-idp issues and demands a <b>platform-tier role</b>. See below.
   * </ol>
   *
   * <p><b>That last arm is a ruling and it is the one to read before changing anything here.</b>
   * "An absent claim is a mismatch, never a wildcard" is {@code MachineAuth.requireClaim}'s rule and
   * it stays true of it: that method answers "does your claim cover <em>this target</em>", and for
   * that question absence must never mean yes. This door asks a different question — "what may I
   * evaluate <em>for</em> you" — and the platform's own answer, measured 2026-09-04, is that its
   * agent and operator credentials carry <b>no structured claims at all</b>: a commissioned
   * workspace client's token holds {@code groups} of {@code qits:system}, {@code
   * qits-platform:system} and {@code qits:admin}, and nothing else, and it pushes protected refs at
   * qits-githost on exactly that. Demanding a claim qits-idp does not mint made this the one door in
   * the service no real machine caller could open, while {@code POST /ci/api/runs/{runId}/retry} —
   * which starts a build for any repository at all — has always taken a role and no claim.
   *
   * <p>So an unscoped machine caller is admitted <b>on the platform-tier role</b> and not on the
   * ordinary one. A project-scoped client that holds {@code qits:system} alone still cannot act
   * across the catalogue: it must carry its {@code project} and gets narrowed to it. What widened is
   * exactly the set of callers that already hold a platform-wide credential.
   */
  private String scopeOf() {
    if (!MachineIdentity.isMachine(identity)) {
      return null;
    }
    machineAuth.require();
    String project = MachineIdentity.claim(identity, QitsClaims.PROJECT).orElse(null);
    if (project == null) {
      if (!identity.hasRole(PLATFORM_SYSTEM_ROLE) && !identity.hasRole(PLATFORM_ADMIN_ROLE)) {
        throw new ForbiddenException(
            "Token carries no "
                + QitsClaims.PROJECT
                + " claim and no platform-tier role, so it names nothing to evaluate");
      }
      return null;
    }
    return QitsClaims.ANY.equals(project) ? null : project;
  }

  /** 503 with the same body, and the standard way to say when to come back. */
  private static Response unavailable(TriggerEventResult result) {
    return Response.status(Response.Status.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        .entity(result)
        .build();
  }

  private static String eventId(String supplied) {
    if (supplied == null || supplied.isBlank()) {
      return UUID.randomUUID().toString();
    }
    try {
      return UUID.fromString(supplied.trim()).toString();
    } catch (IllegalArgumentException notAUuid) {
      throw new BadRequestException("Event id must be a UUID");
    }
  }

  private static Instant occurredAt(String supplied) {
    if (supplied == null || supplied.isBlank()) {
      return Instant.now();
    }
    try {
      return Instant.parse(supplied.trim());
    } catch (RuntimeException notAnInstant) {
      throw new BadRequestException("Event occurredAt must be an ISO-8601 instant");
    }
  }

  private String payloadText(JsonNode payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception unserializable) {
      // A tree that came off the wire always writes back out; this is the compiler's demand, not a
      // case anyone can reach.
      throw new BadRequestException("Event payload could not be read");
    }
  }
}
