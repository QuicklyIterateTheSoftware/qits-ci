package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.auth.MachineAuth;
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
 * The only write surface of {@code /ci/api}, and the one place here that guards a machine caller;
 * hidden from the OpenAPI document (a wire/system API — like the capture/OTLP receivers — so {@code
 * docs/openapi.yml} and the generated Angular client stay untouched).
 *
 * <p>{@code POST /ci/api/events/post-receive} is a cross-repo contract: qits-artifacts' {@code
 * CiPostReceiveNotifier} POSTs to exactly this path via its {@code qits.ci.intake-url}, and it is
 * fire-and-forget — a delivery failure is logged at debug and nothing else. A mismatch here
 * therefore raises no error anywhere; CI just stops running. The path carries no {@code ci}
 * segment of its own because {@code quarkus.rest.path=/ci/api} already says it twice over.
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
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CiEventController {

  @Inject CiRunService runService;

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
}
