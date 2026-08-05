package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The CI event intake (docs/epics/qits-ci/) — the wire contract between the git host's post-receive
 * hook and ci, kept HTTP even in-process so an extracted ci service receives the identical payload.
 * The write surface of {@code /ci/api} that guards a machine caller.
 *
 * <p>{@code POST /ci/api/events/post-receive} is a cross-repo contract: qits-artifacts' {@code
 * CiPostReceiveNotifier} POSTs to exactly this path via its {@code qits.ci.intake-url}, and it is
 * fire-and-forget — a delivery failure is logged at debug and nothing else. A mismatch here
 * therefore raises no error anywhere; CI just stops running. The path carries no {@code ci}
 * segment of its own because {@code quarkus.rest.path=/ci/api} already says it twice over.
 * It is hidden from the OpenAPI document: machine-only, guarded, and its wire contract is written
 * down in qits-artifacts rather than here.
 *
 * <p>{@code POST /ci/api/events/trigger} is the sibling and is <b>not</b> hidden, on the same
 * criterion pointed the other way: a person invokes it on purpose and its contract is written down
 * nowhere else. It replays a domain event by hand, so both operations live on this one resource —
 * the guard, the path and the "accept and return" shape are the same in each.
 *
 * <h2>What the guard names, and why it is the repoId</h2>
 *
 * <p>The guard is {@code requireProject(event.repoId())} — the {@code project} claim of the caller's
 * token is matched against the <b>repository id</b> of the push. That is a deliberate choice and it
 * needs saying, because the claim is named after something else.
 *
 * <p><b>qits-ci cannot name a project.</b> It has no project entity, no lookup and no
 * qits-projects client; {@code ci_run.repo_id} is a plain string with no relation to anything (see
 * V1's own comment, {@code CiCandidateRepos}, and {@code SoftwareRelease}: "no projectId — qits-ci
 * never learns one"). Deriving one would mean a synchronous call to qits-projects on the push path,
 * a new config key and a new way for a push to fail — to gain precision over a mapping this service
 * would then have to trust anyway. The repoId is the finest thing it can honestly assert, so that is
 * what is asserted.
 *
 * <p><b>Both callers still work.</b> qits-artifacts hosts every project's repositories, so its
 * client is granted {@code project=*}, which covers any value (see qits-auth-core's wildcard). A
 * deployment that wants a narrower grant spells repository ids in the {@code project} claim, and
 * that is the cost of the decision. Revisit it when qits-ci gains a real project seam — and change
 * the grants in the same commit.
 *
 * <p><b>The manual trigger names no repository, so it demands them all</b> — {@code
 * requireProject(QitsClaims.ANY)}, a token granted {@code project=*}. An event is evaluated against
 * every candidate repository, so the narrowest honest grant for it is every project rather than one,
 * and a token scoped to a single repository is a 403 there while it is accepted at the intake.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CiEventController {

  @Inject CiRunService runService;

  @Inject CiEventTriggerService triggers;

  @Inject ObjectMapper objectMapper;

  @Inject MachineAuth machineAuth;

  /** One updated branch ref of a received push. {@code oldSha} is all-zeros on branch creation. */
  public record PostReceiveEvent(
      @NotBlank String repoId, @NotBlank String branch, String oldSha, @NotBlank String newSha) {}

  /**
   * Accepts the event and returns immediately — the run executes on ci's worker.
   *
   * <p>The guard runs before the payload is acted on, and only after validation: an unparseable
   * event is a 400 whether or not the caller could have been let in. With the gate off it returns at
   * once and this endpoint behaves exactly as it did under network trust.
   */
  @POST
  @Path("/post-receive")
  @Operation(hidden = true)
  public Response postReceive(@Valid PostReceiveEvent event) {
    machineAuth.requireProject(event.repoId());
    runService.onPostReceive(event.repoId(), event.branch(), event.oldSha(), event.newSha());
    return Response.accepted().build();
  }

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

  /** The id the evaluation ran under, so a caller can find the runs it caused. */
  public record TriggerEventAccepted(String eventId) {}

  /**
   * Evaluates every repository's {@code .config/qits/ci-event-*.yml} against an event the caller
   * supplies, and runs whichever pipelines select it — for any domain-event trigger type, not one
   * kind of them.
   *
   * <p>The bus stays the primary trigger. This is the second inbound adapter of the same seam: it
   * builds a {@link CiEventTriggerService.Arrival} exactly as {@code bus/CiEventTriggerListener}
   * does, so the engine cannot tell the two apart and nothing web-shaped reaches the {@code ci}
   * module. Evaluation is async on {@code ci-trigger-worker} as ever, which is why this answers
   * <b>202</b> and carries the event id rather than a run id: one event may match any number of
   * trigger files in any number of repositories, and none of them exists yet when this returns.
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
   * matches against {@code triggerEventId} on {@code GET /ci/api/runs} to find what it caused.
   *
   * <p>{@code occurredAt} lands on the run row as the event snapshot's timestamp, exactly as a bus
   * arrival's would, and reaches the step containers as {@code $QITS_EVENT_OCCURRED_AT}. The payload
   * is passed through as the bytes the caller sent, minus insignificant whitespace — a payload
   * copied out of the event log is already canonical and stays that way. It is never bound to a
   * type: the engine {@code readTree}s it and walks it, and inventing a canonicalization here would
   * be a second wire format.
   *
   * <p>The guard demands a token granted every project ({@code project=*}), unlike the intake beside
   * it, which demands the pushed repository. This call names no repository — it is evaluated against
   * every candidate — so the repository-scoped grant it would need is "all of them".
   */
  @POST
  @Path("/trigger")
  @Operation(summary = "Run every event pipeline that selects a caller-supplied domain event")
  @APIResponse(
      responseCode = "202",
      description = "Accepted for evaluation, with the event id it ran under",
      content = @Content(schema = @Schema(implementation = TriggerEventAccepted.class)))
  @APIResponse(
      responseCode = "400",
      description = "Blank name, missing payload, or an unparseable timestamp or id")
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
    triggers.onEvent(
        new CiEventTriggerService.Arrival(
            eventId, request.name(), occurredAt, payloadText(request.payload())));
    return Response.accepted().entity(new TriggerEventAccepted(eventId)).build();
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
