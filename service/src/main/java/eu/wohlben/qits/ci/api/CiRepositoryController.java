package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.dto.CiRunDto;
import eu.wohlben.qits.ci.mapper.CiRunMapper;
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
 * also counts every repository the git host lists, because a repository ci has never built is still
 * a candidate to be triggered. A repository with no run has no history to explore, so it does not
 * belong in an answer a UI draws nodes from.
 *
 * <p>A separate resource rather than a second method on {@code CiRunController}, because {@code
 * @Path("/runs")} is about runs. It is an ordinary JAX-RS resource under {@code quarkus.rest.path},
 * so it adds <b>no literal route</b> and {@code quarkus.quinoa.ignored-path-prefixes} is unchanged —
 * {@code /api} already covers it. And it is a read, so it calls no machine guard — exactly like the
 * run reads.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class CiRepositoryController {

  @Inject CiRunService runService;

  @Inject CiRunMapper mapper;

  public record ListRepositoryIdsResponse(List<String> repositoryIds) {}

  @GET
  @Operation(summary = "The repository ids qits-ci has recorded runs for")
  @APIResponse(responseCode = "200", description = "Distinct repository ids, ascending")
  public ListRepositoryIdsResponse listRepositoryIds() {
    return new ListRepositoryIdsResponse(runService.repositoryIds());
  }

  /**
   * One repository, its newest run on any branch, and its newest run on {@code main}. {@code
   * lastMainRun} is null when the repository has never run on {@code main}, and is frequently the
   * same run as {@code lastRun}.
   *
   * <p>Both slots carry the <b>full</b> {@link CiRunDto} rather than a trimmed shape, for the reason
   * the run listing does: it is small, it is the type every client already binds, and a second
   * "run summary" type would drift from it. What it does not carry is {@code steps} and {@code live},
   * because the mapper's list shape omits them — exactly as {@code GET /ci/api/runs} does.
   */
  public record RepositorySummaryDto(String repositoryId, CiRunDto lastRun, CiRunDto lastMainRun) {}

  public record ListRepositorySummariesResponse(List<RepositorySummaryDto> repositories) {}

  /**
   * The same repositories {@link #listRepositoryIds} answers with, each carrying the two runs a
   * client would otherwise fetch a listing per repository to find.
   *
   * <p><b>It exists because the alternative is a request per repository on every page load.</b> A
   * client drawing "which repositories are there, and how is each doing" had to call {@code GET
   * /ci/api/repositories} and then {@code GET /ci/api/runs?repositoryId=…&limit=1} per id — n+1
   * requests over a gateway for a page that is one question. The n+1 is still there, but it is two
   * indexed top-1 reads inside one process rather than n round trips over HTTP.
   *
   * <p><b>The name is {@code repositories} where the older endpoint says {@code repositoryIds}, and
   * both are right.</b> That one returns bare strings and must not suggest ci owns an object; this
   * one returns objects, and they are objects about <em>runs</em> — a repository id with what ci has
   * recorded against it. ci still owns no repository, and this response says nothing about one that
   * is not a run.
   *
   * <p>Sorted by {@code repositoryId} ascending, the same ordering and for the same reason: a client
   * diffing this against another service's list must not see the order change because the query
   * planner did.
   */
  @GET
  @Path("/summary")
  @Operation(summary = "Each repository with its newest run and its newest run on main")
  @APIResponse(responseCode = "200", description = "One entry per repository, ascending by id")
  public ListRepositorySummariesResponse listRepositorySummaries() {
    return new ListRepositorySummariesResponse(
        runService.repositorySummaries().stream()
            .map(
                summary ->
                    new RepositorySummaryDto(
                        summary.repositoryId(),
                        mapper.toDto(summary.lastRun()),
                        summary.lastMainRun() == null ? null : mapper.toDto(summary.lastMainRun())))
            .toList());
  }
}
