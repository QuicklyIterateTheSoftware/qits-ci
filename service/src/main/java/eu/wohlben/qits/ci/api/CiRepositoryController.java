package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The repository ids qits-ci has recorded runs for — the one read on this surface that is not scoped
 * to a repository, because it is the question "which repositories are there to scope to".
 *
 * <p><b>The name is the contract.</b> The response is {@code {"repositoryIds": […]}} and not {@code
 * {"repositories": […]}}: ci does not own repositories, {@code ci_run.repo_id} is a plain string in
 * ci's own database with no relation to anything, and there is no object here to return. These are
 * ids this instance <em>observed</em>, sorted ascending so the answer is stable.
 *
 * <p><b>Why it exists at all.</b> The run listing takes a mandatory {@code ?repositoryId=} filter,
 * which is right — an unscoped listing of every run on the instance is not a page anyone wants — but
 * it also makes CI activity that nothing else knows about <em>invisible</em>. The explorer at {@code
 * /ci/} walks qits-projects' projects down to their repositories, and every repository a project
 * does not claim would simply not be drawn. On the platform as it stands that is the whole run
 * history: qits-local-up.sh seeds the platform's own bare repositories directly onto the git host
 * with no qits-projects {@code Repository} row, so their runs belong to no project. This endpoint is
 * what lets a client compute that set and show it, rather than quietly omitting it.
 *
 * <p>Deliberately narrower than {@code CiCandidateRepos}, which the trigger engine asks: that one
 * also counts the bare caches on disk, because a repository ci has merely fetched from is still a
 * candidate to be triggered. A repository with no run has no history to explore, so it does not
 * belong in an answer a UI draws nodes from.
 *
 * <p>A separate resource rather than a second method on {@code CiRunController}, because {@code
 * @Path("/runs")} is about runs. It is an ordinary JAX-RS resource under {@code quarkus.rest.path},
 * so it adds <b>no literal route</b> and {@code quarkus.quinoa.ignored-path-prefixes} is unchanged —
 * {@code /api} already covers it. And it is a GET outside {@code events}, so {@link CiTokenFilter}
 * leaves it alone exactly as it leaves the run reads alone.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
public class CiRepositoryController {

  @Inject CiRunService runService;

  public record ListRepositoryIdsResponse(List<String> repositoryIds) {}

  @GET
  @Operation(summary = "The repository ids qits-ci has recorded runs for")
  @APIResponse(responseCode = "200", description = "Distinct repository ids, ascending")
  public ListRepositoryIdsResponse listRepositoryIds() {
    return new ListRepositoryIdsResponse(runService.repositoryIds());
  }
}
