package eu.wohlben.qits.ci.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
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
public class CiRunController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  @Inject CiStepRelay relay;

  @Inject ObjectMapper objectMapper;

  public record ListRunsResponse(List<CiRunDto> runs) {}

  public record CancelRunRequest(String reason) {}

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
