package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The daemon binary this qits-ci is configured to launch: {@code {"daemonVersion": "<hex or
 * blank>"}}.
 *
 * <p><b>The configured pin, never a run row.</b> {@code ci_run.daemon_version} is history — what
 * some run once launched — and the run listing clamps at 100, so the rows cannot even enumerate what
 * this instance has pinned over its life. This answers the different and much smaller question: what
 * would a run started right now download. There is exactly one process that knows it, because it is
 * the one that resolves the url and starts the container, which is why this reads {@link
 * CiDaemonLauncher} rather than a config key of its own.
 *
 * <p><b>Blank is an answer, not an absence.</b> It means this deployment has pinned no daemon —
 * the shipped default, and the honest state of a platform that has not published one yet. A caller
 * deciding anything on this value must treat blank as "no pin" and never as "unknown".
 *
 * <p><b>Who asks.</b> qits-artifacts' daemon-binary GC reads it when it plans a sweep: the blobs a
 * live pin names are the ones it must keep, and an unreachable qits-ci aborts the plan with nothing
 * deleted. That is the same fail-closed shape the docker strategy already has against qits-cd's
 * deployments, and it is why the pin needed a queryable surface at all — the alternative was a
 * hand-maintained allowlist in qits-artifacts that a deployment bumping the pin would forget,
 * arming the GC against its own CI. The client at {@code /ci/} may read the same value to show which
 * daemon the platform runs.
 *
 * <p>Read-only and unguarded, exactly like the run and repository reads. There is no secret here —
 * the digest is already in every step container's environment and on every run row — and this
 * service authenticates nothing anyway (the gateway does).
 *
 * <p>In the OpenAPI document rather than hidden, on this repo's standing criterion. The one
 * operation kept out, {@code POST /ci/api/events/post-receive}, is machine-only, token-guarded and
 * has its wire contract in qits-artifacts; this one is none of those. Its contract lives here, its
 * consumer is another first-party service reading it fail-closed, and a client draws from it — so a
 * change to the shape belongs in a reviewable diff.
 *
 * <p>An ordinary JAX-RS resource under {@code quarkus.rest.path}, so it adds <b>no literal route</b>
 * and {@code quarkus.quinoa.ignored-path-prefixes} is unchanged — {@code /api} already covers it.
 * Note the address is {@code /ci/api/daemon} and the control socket is {@code /ci/daemon}: adjacent
 * spellings, unrelated surfaces, and only this one is a resource.
 */
@Path("/daemon")
@Produces(MediaType.APPLICATION_JSON)
public class CiDaemonController {

  @Inject CiDaemonLauncher launcher;

  /**
   * The configured pin. A field rather than a bare string so the answer stays extensible — a caller
   * binding {@code {"daemonVersion": …}} keeps working the day this grows a sibling.
   */
  public record DaemonPinDto(String daemonVersion) {}

  @GET
  @Operation(summary = "The daemon binary this instance is configured to launch")
  @APIResponse(
      responseCode = "200",
      description = "The configured pin; blank when this deployment has pinned no daemon")
  public DaemonPinDto daemonPin() {
    return new DaemonPinDto(launcher.daemonVersion());
  }
}
