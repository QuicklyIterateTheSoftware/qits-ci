package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The read side of {@code /ci/api} (docs/epics/qits-ci/): the recorded green/red per branch,
 * visible without any session (reads are open — the gate is advisory in the MVP). Hidden from the
 * OpenAPI document like the rest of the ci surface.
 *
 * <p>The run is the entity and the repository is a <b>filter</b>, so the listing takes {@code
 * ?repositoryId=} rather than sitting under {@code /repositories/{repoId}/}. ci does not own
 * repositories — {@code ci_run.repo_id} is a plain string in ci's own database with no relation to
 * anything — so addressing runs beneath another context's aggregate claimed a containment that does
 * not exist, and put three services under one gateway prefix. {@code {runId}} stays in the path:
 * there it is identity, not scope.
 */
@Path("/runs")
@Produces(MediaType.APPLICATION_JSON)
public class CiRunController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

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

  /** One run with its steps, exit codes, and captured output. */
  @GET
  @Path("/{runId}")
  @Operation(hidden = true)
  public CiRunDto getRun(@PathParam("runId") String runId) {
    return mapper.toDto(runService.requireRun(runId), runService.stepsFor(runId));
  }
}
