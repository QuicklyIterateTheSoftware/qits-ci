package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.dto.CiLiveStepDto;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The read side of {@code /ci/api} (docs/epics/qits-ci/): the recorded green/red per branch,
 * visible without any session (reads are open — the gate is advisory in the MVP), plus the one write
 * a person performs, cancelling a run.
 *
 * <p>The run is the entity and the repository is a <b>filter</b>, so the listing takes {@code
 * ?repositoryId=} rather than sitting under {@code /repositories/{repoId}/}. ci does not own
 * repositories — {@code ci_run.repo_id} is a plain string in ci's own database with no relation to
 * anything — so addressing runs beneath another context's aggregate claimed a containment that does
 * not exist, and put three services under one gateway prefix. {@code {runId}} stays in the path:
 * there it is identity, not scope.
 *
 * <p><b>The reads take the pair {@code qits:admin, qits:system}; the two run-scoped writes take
 * {@code qits:admin} alone.</b> qits:system is the machine role and qits:admin the human one, and a
 * machine that has to poll a run it asked for — qits-platform-maintenance waits out every bump this
 * way — must not be granted a person's role to do it. What mutates is not widened: {@link #cancelRun}
 * and {@link #retryRun} carry their own method-level list, which replaces the class's rather than
 * adding to it.
 *
 * <p><b>There is one write here a machine really does perform, and it is the exception that proves
 * that rule.</b> {@link #cancelReleaseRequestRuns} is qits-projects withdrawing a release request's
 * CI, addressed by {@code (repoId, releaseRequestId)} because the runs it stops carry a sha nobody
 * pushed. It keeps the class's role pair — a peer service and an operator both legitimately call it —
 * and adds this resource's only {@code MachineAuth} check, on the machine arm. The rule that
 * matters is the one {@code MachineGuardTest} enforces: a new write that omits the guard ships
 * unguarded and nothing says so, so every write on this surface has a case in that file.
 *
 * <p><b>Nothing here is hidden from the OpenAPI document any more.</b> The two reads used to carry
 * {@code @Operation(hidden = true)} on the criterion "does a client consume it, does a person invoke
 * it" — machine surfaces stay out, the cancel button goes in. The criterion was right and its answer
 * changed: qits-spa-ci reads both of these on every page it draws, so they are the JSON API a
 * first-party client consumes. Leaving them hidden would mean {@code docs/openapi.yml} — a file this
 * repo commits precisely so that a surface change shows up as a diff — omitted the entire contract
 * that client depends on, and a breaking change to {@link CiRunDto} would have landed with an empty
 * diff. The intake in {@code CiEventController} stays hidden: it really is machine-only,
 * token-guarded, and has a cross-repo wire contract with qits-artifacts.
 */
@Path("/runs")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class CiRunController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  @Inject CiStepRelay relay;

  @Inject ObjectMapper objectMapper;

  /** The machine half of {@link #cancelReleaseRequestRuns}' guard; see that method. */
  @Inject MachineAuth machineAuth;

  /** Read only to tell a machine caller from a forwarded session — never as a security state. */
  @Inject SecurityIdentity identity;

  public record ListRunsResponse(List<CiRunDto> runs) {}

  public record CancelRunRequest(String reason) {}

  /**
   * What qits-projects sends to withdraw a release request's CI: the repository, and the request
   * whose work is no longer wanted. Both are required — see {@link #cancelReleaseRequestRuns}.
   */
  public record CancelReleaseRequestRunsRequest(
      @Schema(description = "The repository whose runs to stop", required = true) String repoId,
      @Schema(description = "The release request whose work is withdrawn", required = true)
          String releaseRequestId) {}

  /** Which runs the cancellation actually reached — empty when there was nothing left in flight. */
  public record CancelReleaseRequestRunsResponse(List<String> runIds) {}

  /** The run a retry created; poll it like any other. */
  public record RetryRunResponse(String runId) {}

  /**
   * A repository's runs, newest-first — without step output (fetch a single run for that). The
   * filter is required and validated: an unscoped listing would return every run on the instance,
   * and a missing one must say so rather than answer with an empty list.
   *
   * <p>{@code ?limit=} is optional and bounds the answer to the newest {@code n}; absent, the
   * listing is unbounded, so nothing that predates the parameter changes. The ordering is what makes
   * that a total answer rather than an arbitrary sample. There is deliberately <b>no {@code
   * ?offset=} and no cursor</b>: an offset over a list that grows at the head re-shows rows under
   * concurrent inserts, and the two things anyone actually wants — the newest n, then one specific
   * run — are both already covered. A real history walk wants {@code before=<createdAt>}, and that
   * waits for a requirement.
   *
   * <p>The parameter is taken as a {@code String} and parsed here rather than bound to an {@code
   * Integer}, because JAX-RS answers a query-parameter conversion failure with a <b>404</b>. A
   * mistyped limit is a bad request, and it must arrive as one through {@link CiExceptionMapper}'s
   * {@code {"message": …}} envelope like every other rejected input on this surface. A present but
   * empty value is read as absent: {@code ?limit=} is what an unfilled template produces, and
   * refusing it buys nothing.
   */
  @GET
  @Operation(summary = "List a repository's CI runs, newest first")
  @APIResponse(responseCode = "200", description = "The repository's runs, without step output")
  @APIResponse(responseCode = "400", description = "The repository id is missing or invalid, or the limit is not a positive integer")
  public ListRunsResponse listRuns(
      @Parameter(description = "The repository whose runs to list — required", required = true)
          @QueryParam("repositoryId")
          String repositoryId,
      // Declared as the integer it is, though it binds as a String: the document describes the
      // contract, and taking it as a String is how a bad value becomes a 400 instead of a 404.
      @Parameter(
              description = "Return only the newest n runs; omit for all of them",
              schema = @Schema(type = SchemaType.INTEGER, minimum = "1"))
          @QueryParam("limit")
          String limit) {
    CiIdentifiers.requireRepoId(repositoryId);
    return new ListRunsResponse(
        runService.runsFor(repositoryId, parseLimit(limit)).stream().map(mapper::toDto).toList());
  }

  /** {@code null} for absent or blank; a positive int; otherwise a 400. */
  private static Integer parseLimit(String limit) {
    if (limit == null || limit.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(limit.trim());
    } catch (NumberFormatException notANumber) {
      throw new BadRequestException("Invalid limit");
    }
  }

  /**
   * Everything CI has accepted and not finished — {@code QUEUED} or {@code RUNNING} — across every
   * repository, newest first. No parameters, and step output is not carried (fetch a single run for
   * that).
   *
   * <p><b>The one read here that is not scoped to a repository</b>, and the exception is the whole
   * point: "what is CI doing right now" has no repository to scope to, and a client that had to ask
   * per repository would have to know the repositories first and would still see a different instant
   * in each answer. It became answerable only when a queued run became a row — before that, half of
   * this list lived in an executor's queue where nothing could read it.
   *
   * <p>It carries no {@code ?limit=} because it needs none: what is active is bounded by accepted
   * work and the configured worker pool, not by how long the instance has been up.
   *
   * <p>{@code /active} is a literal segment and {@link #getRun}'s is a template, so JAX-RS matches
   * this one first — a run whose id is the string {@code active} is not addressable, and no run id
   * this service mints ever is.
   */
  @GET
  @Path("/active")
  @Operation(summary = "Every queued or running CI run, all repositories, newest first")
  @APIResponse(responseCode = "200", description = "The active runs, without step output")
  public ListRunsResponse listActiveRuns() {
    return new ListRunsResponse(runService.activeRuns().stream().map(mapper::toDto).toList());
  }

  /**
   * The newest finished runs — anything that is neither {@code QUEUED} nor {@code RUNNING} — across
   * every repository, newest first. Step output is not carried, exactly as the two listings above.
   *
   * <p><b>The sibling of {@code /active}, and it exists because that one cannot answer this.</b> A
   * client drawing "what is CI doing, and what did it just finish" had no way to ask the second half:
   * the active list holds only the two non-terminal statuses, the repository listing demands a
   * repository, and a per-repository fan-out is the n+1 {@code
   * CiRepositoryController#listRepositorySummaries} exists to spare a client — and it would still be
   * wrong, since two of the newest five finished runs can belong to one repository. The two lists are
   * complements over the same table, so a run that leaves one arrives in the other.
   *
   * <p>It <b>does</b> carry {@code ?limit=} where {@code /active} does not, and the asymmetry is the
   * whole difference between them: what is active is bounded by accepted work and the configured
   * worker pool, while what is finished grows for as long as the instance has been up. Absent means
   * {@link CiRunService#DEFAULT_FINISHED_LIMIT} rather than unbounded — the opposite of the
   * repository listing's default, because there is no repository here to make "all of them" a bounded
   * question. It is parsed by the same {@link #parseLimit} for the same 400-not-404 reason, and an
   * ask above {@link CiRunService#MAX_FINISHED_LIMIT} is answered with that many rather than refused.
   *
   * <p>{@code /finished} is a literal segment, so JAX-RS matches it before {@link #getRun}'s
   * template — the same ranking {@code /active} relies on, asserted rather than assumed in {@code
   * CiPipelineBoundaryTest}. It adds no Vert.x route of its own, so {@code
   * quarkus.quinoa.ignored-path-prefixes} is unchanged: {@code /api} already covers it.
   */
  @GET
  @Path("/finished")
  @Operation(summary = "The newest finished CI runs, all repositories, newest first")
  @APIResponse(responseCode = "200", description = "The finished runs, without step output")
  @APIResponse(responseCode = "400", description = "The limit is not a positive integer")
  public ListRunsResponse listFinishedRuns(
      @Parameter(
              description = "Return the newest n finished runs; omit for 5, capped at 100",
              schema = @Schema(type = SchemaType.INTEGER, minimum = "1", maximum = "100"))
          @QueryParam("limit")
          String limit) {
    return new ListRunsResponse(
        runService.finishedRuns(parseLimit(limit)).stream().map(mapper::toDto).toList());
  }

  /**
   * One run with its steps, exit codes and captured output — plus, while it is running, the {@code
   * live} object holding the step in flight and what it has printed so far.
   *
   * <p>Step rows are written at each step's end, so a mid-run poll legitimately sees fewer steps
   * than the pipeline declared; {@code live} is what makes that legible instead of looking like a
   * run with holes in it. It comes from memory rather than the database and is dropped the moment
   * the run closes — after that the persisted tails are the whole record. Following along is
   * polling this endpoint; there is no SSE and no WebSocket for it.
   */
  @GET
  @Path("/{runId}")
  @Operation(summary = "One CI run with its steps, output and — while it runs — its live step")
  @APIResponse(responseCode = "200", description = "The run")
  @APIResponse(responseCode = "404", description = "No such run")
  public CiRunDto getRun(@PathParam("runId") String runId) {
    CiRun run = runService.requireRun(runId);
    List<CiStep> steps = runService.stepsFor(runId);
    CiLiveStepDto live =
        run.status == CiRunStatus.RUNNING
            ? relay
                .snapshot(runId)
                // A step's buffer outlives it by the one transaction that writes its row, and the
                // next step's buffer replaces it. `live` means "the step with no row yet", so
                // during that window it means nothing — a client must never be handed the same
                // step twice, once as a row and once as live.
                .filter(snapshot -> steps.stream().noneMatch(s -> s.stepIndex == snapshot.stepIndex()))
                .map(snapshot -> new CiLiveStepDto(snapshot.stepIndex(), snapshot.output()))
                .orElse(null)
            : null;
    return mapper.toDto(run, steps, live);
  }

  /**
   * Stop a running run: its in-flight step's container is asked to die, that step is recorded failed
   * with "cancelled" in its output, and the rest are skipped.
   *
   * <p>202 rather than 200, because the run is not finished when this returns — the container has
   * been asked, its daemon still has to answer with a terminal frame, and the worker still has rows
   * to write. Poll the run for the outcome. Cancelling a run that has already finished is a 409: it
   * has nothing to stop, and a cheerful 202 would be a claim the caller cannot check.
   *
   * <p>A run that is still {@code QUEUED} can be cancelled too, and it is the cheap case — there is
   * no container to ask, so the run is recorded {@code FAILED} with no steps and the worker never
   * picks it up. Still a 202: the shape of the answer does not change with how far along the run
   * was, and the caller polls either way.
   *
   * <p>Deliberately <b>not</b> {@code @Operation(hidden = true)}, unlike everything else in this
   * service. The intake and the run reads are machine surfaces; this one is a button a person
   * presses, so it belongs in the API document. It sits on the same deployment-policy-guarded
   * surface as the run reads and carries no token of its own — the single-user stance.
   */
  @POST
  @Path("/{runId}/cancel")
  // A method-level list REPLACES the class-level one rather than adding to it, which is exactly what
  // this needs: the reads above take the pair, and cancelling a build stays a person's.
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Cancel a queued or running CI run")
  @APIResponse(responseCode = "202", description = "The run has been stopped or asked to stop")
  @APIResponse(responseCode = "404", description = "No such run")
  @APIResponse(responseCode = "409", description = "The run has already finished, so there is nothing to stop")
  public Response cancelRun(
      @PathParam("runId") String runId,
      @org.eclipse.microprofile.openapi.annotations.parameters.RequestBody(
              required = false,
              description = "Optional human-readable cancellation reason",
              content =
                  @org.eclipse.microprofile.openapi.annotations.media.Content(
                      schema = @Schema(implementation = CancelRunRequest.class)))
          String payload) {
    runService.cancel(runId, cancellationReason(payload));
    return Response.accepted().build();
  }

  /**
   * Stop every unfinished run one repository has for one release request — the door qits-projects
   * knocks on when a request is withdrawn, closed or re-scoped.
   *
   * <p><b>Addressed by the pair, not by run id, and that is the contract rather than a convenience.</b>
   * The runs it cancels were triggered by a {@code ReleaseRequestChanged} and build the request's
   * backing branch, whose sha is a fold nobody pushed and is rewritten by the next re-fold. So the
   * caller holds no run id and no stable sha — the request id is the only handle that survives, which
   * is exactly why {@code ci_run.release_request_id} exists. Both halves are required: one request
   * folds many repositories and one repository carries many open requests, so either alone would
   * cancel a sibling's build.
   *
   * <p><b>202 and nothing else on the happy path.</b> A queued run is terminal before this returns; a
   * running one has only been <em>asked</em> to stop, and its container still has to answer. Nothing
   * left in flight is a 202 with an empty list rather than a 404: the caller asked for a state — this
   * request's work is not running — and that state holds. Repeating the call is therefore safe.
   *
   * <p><b>Nothing cancelled here publishes a gating verdict.</b> A {@code CANCELLED} run announces
   * neither {@code BuildSuccessful} nor {@code BuildFailed}, so the release gate on the other side
   * never sees a withdrawn request's stopped build as a failure. That is {@code CiRunService}'s
   * standing contract, and it is the property this endpoint depends on.
   *
   * <p><b>The guard is the machine one, and a person still reaches it.</b> A machine caller is judged
   * on its token exactly as {@code CiEventController}'s is: right audience, and {@code project=*},
   * because qits-ci owns no project entity and can resolve a repository to no project. A caller that
   * presents no token is not a machine and never reaches the check — it has already been judged by
   * the class-level roles, which is the forwarded {@code X-Qits-User}/{@code X-Qits-Roles} session an
   * operator arrives on. Both callers are real for this route, which is why the check is on the
   * machine arm rather than over the whole method.
   */
  @POST
  @Path("/cancellations")
  @Consumes(MediaType.APPLICATION_JSON)
  @Operation(summary = "Cancel every unfinished run a repository has for one release request")
  @APIResponse(
      responseCode = "202",
      description = "The request's runs have been stopped or asked to stop",
      content = @Content(schema = @Schema(implementation = CancelReleaseRequestRunsResponse.class)))
  @APIResponse(responseCode = "400", description = "The repository id or the release request id is missing or invalid")
  public Response cancelReleaseRequestRuns(CancelReleaseRequestRunsRequest request) {
    if (request == null) {
      throw new BadRequestException("A repository id and a release request id are required");
    }
    CiIdentifiers.requireRepoId(request.repoId());
    String releaseRequestId = requireReleaseRequestId(request.releaseRequestId());
    if (MachineIdentity.isMachine(identity)) {
      machineAuth.requireProject(QitsClaims.ANY);
    }
    return Response.accepted()
        .entity(
            new CancelReleaseRequestRunsResponse(
                runService.cancelReleaseRequestRuns(request.repoId(), releaseRequestId)))
        .build();
  }

  /**
   * A release request id is qits-projects' opaque string and this service validates it as one:
   * present, and short enough to be the id of a request this platform issued. It reaches nothing but
   * a bound query parameter, so there is no shape to check beyond that — inventing one here would be
   * this service holding an opinion about another context's identifiers.
   */
  private static String requireReleaseRequestId(String releaseRequestId) {
    if (releaseRequestId == null || releaseRequestId.isBlank()) {
      throw new BadRequestException("A release request id is required");
    }
    String trimmed = releaseRequestId.trim();
    if (trimmed.length() > MAX_RELEASE_REQUEST_ID_LENGTH) {
      throw new BadRequestException(
          "A release request id is at most " + MAX_RELEASE_REQUEST_ID_LENGTH + " characters");
    }
    return trimmed;
  }

  /** What {@code ci_run.release_request_id} holds, so a longer one names no run here. */
  private static final int MAX_RELEASE_REQUEST_ID_LENGTH = 255;

  /**
   * Run this run's pipeline again, unchanged — the same repository, the same trigger file, the same
   * commit, the same release request.
   *
   * <p>A person's button, for the case a red run is about the platform rather than about the code: a
   * flaked container, a registry that was down, a step that hit its deadline on a busy host. Nothing
   * about the work has changed, so nothing needs re-folding and no new event needs waiting for — the
   * new run builds the very sha the old one built, and its verdict lands on that commit exactly as
   * the first one's would have.
   *
   * <p>202 rather than 201: the answer is a run that has been <em>accepted</em>, is {@code QUEUED}
   * and has not started, so the caller polls {@code GET /ci/api/runs/{runId}} with the id in the body
   * like it does after a trigger. Retrying a run that has not finished is a 409 — the question is
   * still being answered, and two runs racing for one verdict is not what was asked for.
   *
   * <p>It is a write, so it carries the same {@code qits:admin} the cancel does rather than the
   * class's read pair: starting somebody's build is not a thing a peer service does either.
   */
  @POST
  @Path("/{runId}/retry")
  @jakarta.annotation.security.RolesAllowed("qits:admin")
  @Consumes(MediaType.WILDCARD)
  @Operation(summary = "Run a finished CI run's pipeline again, at the same commit")
  @APIResponse(
      responseCode = "202",
      description = "A new run has been accepted and queued",
      content = @Content(schema = @Schema(implementation = RetryRunResponse.class)))
  @APIResponse(responseCode = "404", description = "No such run")
  @APIResponse(responseCode = "409", description = "The run has not finished, so there is nothing to retry yet")
  public Response retryRun(@PathParam("runId") String runId) {
    return Response.accepted().entity(new RetryRunResponse(runService.retry(runId).id)).build();
  }

  private String cancellationReason(String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      JsonNode request = objectMapper.readTree(payload);
      JsonNode reason = request == null ? null : request.get("reason");
      if (reason == null || reason.isNull()) {
        return null;
      }
      if (!reason.isTextual()) {
        throw new BadRequestException("Cancellation reason must be a string");
      }
      return reason.textValue();
    } catch (BadRequestException e) {
      throw e;
    } catch (Exception e) {
      throw new BadRequestException("Invalid cancellation request");
    }
  }
}
