package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.daemonhost.CiStepRelay;
import eu.wohlben.qits.ci.dto.CiLiveStepDto;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStep;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
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
 * <p>The two reads stay hidden from the OpenAPI document like the rest of the machine surface.
 * <b>Cancellation does not</b> — it is the one operation here a human invokes on purpose, so it
 * belongs in the document a client is generated from, and it is why {@code docs/openapi.yml} carries
 * a path at all.
 */
@Path("/runs")
@Produces(MediaType.APPLICATION_JSON)
public class CiRunController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  @Inject CiStepRelay relay;

  public record ListRunsResponse(List<CiRunDto> runs) {}

  /**
   * A repository's runs, newest-first — without step output (fetch a single run for that). The
   * filter is required and validated: an unscoped listing would return every run on the instance,
   * and a missing one must say so rather than answer with an empty list.
   */
  @GET
  @Operation(hidden = true)
  public ListRunsResponse listRuns(@QueryParam("repositoryId") String repositoryId) {
    CiIdentifiers.requireRepoId(repositoryId);
    return new ListRunsResponse(
        runService.runsFor(repositoryId).stream().map(mapper::toDto).toList());
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
  @Operation(hidden = true)
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
   * to write. Poll the run for the outcome. Cancelling anything that is not running is a 409: a
   * finished run has nothing to stop, and a cheerful 202 would be a claim the caller cannot check.
   *
   * <p>Deliberately <b>not</b> {@code @Operation(hidden = true)}, unlike everything else in this
   * service. The intake and the run reads are machine surfaces; this one is a button a person
   * presses, so it belongs in the API document. It sits on the same deployment-policy-guarded
   * surface as the run reads and carries no token of its own — the single-user stance.
   */
  @POST
  @Path("/{runId}/cancel")
  @Operation(summary = "Cancel a running CI run")
  @APIResponse(responseCode = "202", description = "The run's container has been asked to stop")
  @APIResponse(responseCode = "404", description = "No such run")
  @APIResponse(responseCode = "409", description = "The run is not running, so there is nothing to stop")
  public Response cancelRun(@PathParam("runId") String runId) {
    runService.cancel(runId);
    return Response.accepted().build();
  }
}
