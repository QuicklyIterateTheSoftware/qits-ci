package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.ci.error.BadRequestException;
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
 * <p><b>The push intake used to live here and is gone.</b> {@code POST
 * /ci/api/events/post-receive} was the wire contract between the git host's post-receive hook and
 * ci — {@code {repoId, branch, oldSha, newSha}}, fire and forget, with a delivery failure logged at
 * debug on the sender's side and CI simply not running. qits-githost publishes {@code
 * SCMPublishCommit} through the event log instead, and {@code bus/ScmPublishCommitListener} consumes
 * it durably, so a qits-ci that was down while a push landed reads it back. What was an endpoint,
 * a DTO, a machine guard and a replay-by-hand trick is now one listener bean.
 *
 * <p>This resource is therefore no longer a pair, and the operation that is left is the one that was
 * <b>not</b> hidden from the OpenAPI document: a person invokes it on purpose and its contract is
 * written down nowhere else. It <b>evaluates before it answers</b>, because the event it carries is
 * on no log and nothing anywhere can offer it a second time — see {@link #trigger}.
 *
 * <h2>What the guard names</h2>
 *
 * <p><b>The manual trigger names no repository, so it demands them all</b> — {@code
 * requireProject(QitsClaims.ANY)}, a token granted {@code project=*}. An event is evaluated against
 * every candidate repository, so the narrowest honest grant for it is every project rather than one,
 * and a token scoped to a single repository is a 403 here.
 *
 * <p><b>qits-ci cannot name a project</b>, which is why the claim it reads is the coarse one. It has
 * no project entity, no lookup and no qits-projects client; {@code ci_run.repo_id} is a plain string
 * with no relation to anything (see V1's own comment, {@code CiCandidateRepos}, and {@code
 * SoftwareRelease}: "no projectId — qits-ci never learns one"). Revisit that when qits-ci gains a
 * real project seam — and change the grants in the same commit.
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
   * <p>The guard demands a token granted every project ({@code project=*}). This call names no
   * repository — it is evaluated against every candidate — so the repository-scoped grant it would
   * need is "all of them".
   */
  @POST
  @Path("/trigger")
  @Operation(summary = "Run every event pipeline that selects a caller-supplied domain event")
  @APIResponse(
      responseCode = "200",
      description = "Evaluated, with the runs it recorded and which repositories it could ask",
      content = @Content(schema = @Schema(implementation = TriggerEventResult.class)))
  @APIResponse(
      responseCode = "400",
      description = "Blank name, missing payload, or an unparseable timestamp or id")
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

    machineAuth.requireProject(QitsClaims.ANY);
    CiEventTriggerService.Evaluation done;
    try {
      done =
          triggers.evaluateNow(
              new CiEventTriggerService.Arrival(
                  eventId, request.name(), occurredAt, payloadText(request.payload())));
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
