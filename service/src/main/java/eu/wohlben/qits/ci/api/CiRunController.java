package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The read side of {@code /api/ci} (docs/epics/qits-ci/): the recorded green/red per branch,
 * visible without any session (reads are open — the gate is advisory in the MVP). Hidden from the
 * OpenAPI document like the rest of the ci surface.
 */
@Path("/ci")
@Produces(MediaType.APPLICATION_JSON)
public class CiRunController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  public record ListRunsResponse(List<CiRunDto> runs) {}

  /** A repository's runs, newest-first — without step output (fetch a single run for that). */
  @GET
  @Path("/repositories/{repoId}/runs")
  @Operation(hidden = true)
  public ListRunsResponse listRuns(@PathParam("repoId") String repoId) {
    return new ListRunsResponse(runService.runsFor(repoId).stream().map(mapper::toDto).toList());
  }

  /** One run with its steps, exit codes, and captured output. */
  @GET
  @Path("/runs/{runId}")
  @Operation(hidden = true)
  public CiRunDto getRun(@PathParam("runId") String runId) {
    return mapper.toDto(runService.requireRun(runId), runService.stepsFor(runId));
  }
}
