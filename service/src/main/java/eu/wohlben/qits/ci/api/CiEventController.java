package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The CI event intake (docs/epics/qits-ci/) — the wire contract between the git host's post-receive
 * hook and ci, kept HTTP even in-process so an extracted ci service receives the identical payload.
 * The only write surface of {@code /api/ci} (token-guarded by {@link CiTokenFilter}); hidden from
 * the OpenAPI document (a wire/system API — like the capture/OTLP receivers — so {@code
 * docs/openapi.yml} and the generated Angular client stay untouched).
 */
@Path("/ci/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CiEventController {

  @Inject CiRunService runService;

  /** One updated branch ref of a received push. {@code oldSha} is all-zeros on branch creation. */
  public record PostReceiveEvent(
      @NotBlank String repoId, @NotBlank String branch, String oldSha, @NotBlank String newSha) {}

  /** Accepts the event and returns immediately — the run executes on ci's worker. */
  @POST
  @Path("/post-receive")
  @Operation(hidden = true)
  public Response postReceive(@Valid PostReceiveEvent event) {
    runService.onPostReceive(event.repoId(), event.branch(), event.oldSha(), event.newSha());
    return Response.accepted().build();
  }
}
