package eu.wohlben.qits.ci.api;

import eu.wohlben.qits.ci.control.CiDaemonPins;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The daemon binary this qits-ci is configured to launch, and where that answer came from:
 * {@code {"daemonName", "daemonVersion", "previousDaemonVersion", "source"}}
 * (ci-daemon-autoadopt-plan.md §2.7).
 *
 * <p><b>The ladder's top rung, never a run row.</b> {@code ci_run.daemon_version} is history — what
 * some run once launched — and the run listing clamps at 100, so the rows cannot even enumerate what
 * this instance has pinned over its life. This answers the different and much smaller question: what
 * would a run started right now download, and what would it fall back to. There is exactly one
 * place that knows it, {@link CiDaemonPins}, which is why this reads that rather than a config key
 * of its own.
 *
 * <p><b>{@code daemonVersion} blank is an answer, not an absence.</b> It means this deployment has
 * adopted or pinned no daemon — the shipped default, and the honest state of a platform that has not
 * published one yet. A caller deciding anything on this value must treat blank as "no pin" and never
 * as "unknown". {@code source} is {@code "none"} exactly then.
 *
 * <p><b>{@code previousDaemonVersion} is the fallback rung, not row order.</b> It is the next
 * {@code PROVEN} adopted candidate below the current pin — the version qits-ci would actually try
 * next if the current one stopped registering — and it is blank both when the pin is the
 * configured one (there is no rung below it in this ladder) and when no second adopted candidate
 * has proven itself yet.
 *
 * <p><b>Who asks.</b> qits-artifacts' daemon-binary GC reads it when it plans a sweep: the blobs a
 * live pin names are the ones it must keep, and an unreachable qits-ci aborts the plan with nothing
 * deleted. That is the same fail-closed shape the docker strategy already has against qits-cd's
 * deployments, and it is why the pin needed a queryable surface at all — the alternative was a
 * hand-maintained allowlist in qits-artifacts that a deployment bumping the pin would forget,
 * arming the GC against its own CI. {@code daemonName} is reported so that lookup needs no inference
 * — {@code (repository, name, version)} is exactly how qits-artifacts' own {@code daemon_binary} rows
 * are keyed. The client at {@code /ci/} may read the same value to show which daemon the platform
 * runs.
 *
 * <p>Read-only and unguarded, exactly like the run and repository reads. There is no secret here —
 * the version is already in every step container's environment and on every run row — and this
 * service authenticates nothing anyway (the gateway does).
 *
 * <p>In the OpenAPI document rather than hidden, on this repo's standing criterion: does a
 * first-party client consume it, is its contract written down anywhere else. Its contract lives
 * here, its consumer is another first-party service reading it fail-closed, and a client draws from
 * it — so a change to the shape belongs in a reviewable diff. Nothing is hidden any more: the one
 * operation that was, {@code POST /ci/api/events/post-receive}, is gone with the HTTP fan-out it
 * served.
 *
 * <p>An ordinary JAX-RS resource under {@code quarkus.rest.path}, so it adds <b>no literal route</b>
 * and {@code quarkus.quinoa.ignored-path-prefixes} is unchanged — {@code /api} already covers it.
 * Note the address is {@code /ci/api/daemon} and the control socket is {@code /ci/daemon}: adjacent
 * spellings, unrelated surfaces, and only this one is a resource.
 *
 * <p><b>Reads {@link CiDaemonPins#currentAnswer()}, never {@link CiDaemonPins#answer()}.</b> This is
 * a public, unguarded endpoint, so calling the probing method here would let anyone who can reach it
 * launch a probe container on demand. An unprobed candidate answers exactly as it would through
 * {@code answer()} once it is proven or rejected; only the probe side effect differs.
 */
@Path("/daemon")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:system")
public class CiDaemonController {

  @Inject CiDaemonPins pins;

  /**
   * A field per answer rather than a bare string so the shape stays extensible — a caller binding
   * these four keeps working the day this grows a sibling.
   */
  public record DaemonPinDto(
      String daemonName, String daemonVersion, String previousDaemonVersion, String source) {}

  @GET
  @Operation(summary = "The daemon binary this instance is configured to launch")
  @APIResponse(
      responseCode = "200",
      description =
          "The ladder's top rung; daemonVersion and source are blank/\"none\" when this"
              + " deployment has adopted or pinned no daemon")
  public DaemonPinDto daemonPin() {
    CiDaemonPins.Pin pin = pins.currentAnswer();
    return new DaemonPinDto(
        CiDaemonPins.DAEMON_NAME, pin.version(), pin.previousVersion(), pin.source());
  }
}
