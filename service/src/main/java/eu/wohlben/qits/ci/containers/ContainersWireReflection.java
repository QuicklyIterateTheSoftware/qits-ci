package eu.wohlben.qits.ci.containers;

import eu.wohlben.qits.containers.client.ContainersWire;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the native image owes the containers client, and nothing else lives here.
 *
 * <p>The second member of the family {@code bus/EventWireReflection} opened, and it exists for the
 * identical reason: {@code ContainersJson} builds its <b>own</b> {@code ObjectMapper} — so a
 * consuming application's customizers cannot reach what the client puts on a wire — and a mapper
 * built by hand is invisible to the build step that scans for what needs reflecting on. Without this
 * class the JVM suite stays green and the deployed binary fails on <em>every</em> call, which is
 * exactly what a missing {@code EventWireReflection} cost on 2026-08-06.
 *
 * <p><b>Both directions are on the list, and a record this service only sends needs it as much as
 * one it reads</b>: on the writing side an unregistered record has no components to find. The list
 * is closed, every entry is nested in one class, and the client's own README carries it as a paste
 * rather than a derivation. <b>A JVM test cannot catch a missing entry</b> — on a JVM these types
 * reflect whether anyone registered them or not — so the guard is that this list and that README
 * are the same list.
 */
@RegisterForReflection(
    targets = {
      ContainersWire.EnsureRequest.class,
      ContainersWire.Spec.class,
      ContainersWire.Policy.class,
      ContainersWire.Security.class,
      ContainersWire.VolumeMount.class,
      ContainersWire.SharedMount.class,
      ContainersWire.Recreate.class,
      ContainersWire.PolicyType.class,
      ContainersWire.PullPolicy.class,
      ContainersWire.Envelope.class,
      ContainersWire.State.class,
      ContainersWire.Endpoint.class,
      ContainersWire.Listing.class,
      ContainersWire.LogTail.class,
      ContainersWire.DeleteOutcome.class,
      ContainersWire.Destroyed.class,
      ContainersWire.DestroyAllOutcome.class,
      ContainersWire.VolumeEnvelope.class,
      ContainersWire.ErrorBody.class,
      ContainersWire.Desired.class,
      ContainersWire.Observed.class,
      ContainersWire.VolumeState.class
    })
public final class ContainersWireReflection {

  private ContainersWireReflection() {}
}
