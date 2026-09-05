package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.events.BuildFailed;
import eu.wohlben.qits.ci.events.BuildSuccessful;
import eu.wohlben.qits.ci.events.SoftwareRelease;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus binds to and from JSON, told to native-image. No code, no bean, nothing at
 * runtime: the annotation is the entire content, and this class exists so that the annotation has
 * somewhere to live that can say why.
 *
 * <p><b>Why nothing registered these automatically.</b> Quarkus registers reflection for the classes
 * <em>it</em> knows are serialized — a REST resource's parameters and return types, a config
 * mapping, whatever the CDI {@code ObjectMapper} is handed. {@code CanonicalJson} builds its
 * <b>own</b> {@code ObjectMapper} by hand, deliberately and permanently: the canonical form is a
 * wire contract another service compares byte-for-byte, so it must not be downstream of any
 * application's {@code ObjectMapperCustomizer} (that class's javadoc argues it in full). Correct,
 * and this is the price — to the build step scanning for what needs reflecting on, that mapper and
 * everything it touches are invisible. Do not "fix" a recurrence by injecting the CDI mapper.
 *
 * <p><b>What it cost, measured on the deployed binary (2026-07-31).</b> Every green build's publish
 * died inside {@code CanonicalJson}'s writer with Jackson's {@code No serializer found for class
 * eu.wohlben.qits.ci.events.BuildSuccessful … native image, you may need to configure reflection} —
 * no properties discovered, because a record with no reflection metadata has no components to find.
 * The throw happens while the envelope is being built, so the event never reached the outbox either:
 * not a delayed delivery, a lost one, with a single WARN per green run to say so. The JVM suite and
 * the fast-jar {@code CiPackagedSurfaceIT} were green throughout and <b>structurally had to be</b> —
 * on a JVM every one of these types reflects fine, so there is no assertion either could have made
 * that would have failed. This is the fourth member of the family {@code AGENTS.md} names, green on
 * JVM and dead in native, and the first that is missing metadata rather than a config default.
 *
 * <p><b>Why these types.</b> They are the whole of what crosses the wire: {@link BuildSuccessful} is
 * serialized on the way out and deserialized on the way back in (the round trip is one service),
 * {@link SoftwareRelease} is serialized on the way out and never read back here — qits-ci publishes
 * that name and subscribes to nothing under it — {@link SCMPublishCommit} is another repository's
 * record and is only ever read <em>in</em>, {@link EventEnvelope} is the PUT body, and {@link
 * EventFrame} is what arrives on {@code /events/stream}. A listener for a second event type needs
 * its class added here, and {@code EventWireReflectionTest} asserts that against the registered
 * listener beans rather than leaving it to be remembered.
 *
 * <p><b>An event this service only CONSUMES is on this list for the mirror of the reason a
 * published-only one is.</b> {@link SCMPublishCommit} arrives from qits-githost and used to be
 * <em>bound</em> here with {@code CanonicalJson.payloadTo} — Jackson finding a record's components
 * by reflection again, on the reading side this time. Unregistered, the binary would have thrown on
 * every push and settled it unbuilt as poison: CI stopping quietly, with one WARN per push. That
 * the class comes from a jar another repository publishes changed nothing — the registration is a
 * statement about this image.
 *
 * <p><b>Its binder retired on 2026-09-05 and the entry stays, deliberately.</b> {@code
 * ScmPublishCommitListener} was the last {@code payloadTo} of this type; an ordinary push triggers
 * nothing now, and the same event reaches the generic trigger engine, which <em>walks</em> payloads
 * with {@code readTree} and owes no registration. So the entry buys the binary nothing today. It is
 * kept because the type has not left this image's wire vocabulary — a repository may declare {@code
 * event: SCMPublishCommit} in a trigger file, this repo's own suite round-trips the record through
 * the real {@code CanonicalJson} ({@code bus/ScmPushFrames}), and the day anything binds it again the
 * failure would be native-only and silent. Registering a type nothing binds costs image size;
 * un-registering one something binds costs every occurrence of that event. That is not a symmetric
 * trade.
 *
 * <p><b>An event this service only publishes is exactly as dependent on this list</b>, which is
 * worth stating because "nothing binds it back" reads like a reason to skip it. The failure is on
 * the writing side: {@code CanonicalJson} finds a record's components by reflection, so an
 * unregistered one serializes as a Jackson error or, worse, as a payload missing what the mix-in was
 * supposed to hide.
 *
 * <p><b>And why a mix-in by name — this one is measured, not reasoned.</b> {@code
 * CanonicalJson$QitsEventMixin} is the private nested class that keeps {@code QitsEvent}'s four
 * declared methods — {@code eventId} above all — out of a payload, and Jackson finds its {@code
 * @JsonIgnore}s by calling {@code getDeclaredMethods()} on it, which is reflection like any other.
 * Two binaries were built to settle what that costs. With the three targets and no mix-in entry, a
 * green build's payload was {@code {"branch":…,"commitSha":…,"eventId":"00a32ad6-…","finishedAt":…,
 * "repoId":…,"runId":…}} — no crash, no log, {@code eventId} simply present, which is a <b>wire
 * contract violation</b> and the worse of the two failure modes, since identity is supposed to
 * travel only in the envelope. With the entry, the same build published the five fields and nothing
 * else. So the fix Agent D scoped from the observed stack trace was one class short of complete, and
 * the missing one failed silently. Quarkus registers mix-ins itself when they are declared its way
 * ({@code @JacksonMixin}); this one cannot be, because it belongs to a mapper Quarkus must not
 * reach. It is named as a string because it is private and stays private — a build concern is not a
 * reason to widen a library's encapsulation — and the string cannot rot unnoticed: {@code
 * EventWireReflectionTest} resolves it.
 *
 * <p><b>The trigger engine adds nothing here, and that is worth stating rather than leaving to be
 * rediscovered</b> — it is the natural worry in this repository, since the engine parses YAML from
 * other repositories and JSON from other services on every frame. Neither reflects on a type of ours.
 * The trigger files go through SnakeYAML's {@code SafeConstructor}, which produces {@code java.util}
 * collections and boxed primitives and instantiates no class named by repository content (that is a
 * <em>security</em> property first, and this is the second thing it buys); {@code CiEventTriggerParser}
 * then builds its records by hand from those maps. The event payload is read with {@code readTree}
 * into a {@code JsonNode} and walked — no binding, which is exactly why {@code EventDispatcher} uses
 * the same call to name a frame it could not bind. The only wire type the raw path touches is {@link
 * EventFrame}, already registered above because the typed path touches it too. The provenance fields
 * on {@code CiRunDto} are ordinary REST serialization through the CDI mapper, which Quarkus scans.
 *
 * <p>All of this is in {@code service/} because {@code service/} is where every native concession in
 * this repo lives. Eventstream is a published library and {@code ci-events/} is this repo's
 * vocabulary; the deployable is
 * what gets built into an image, so the deployable is what tells the builder about itself.
 */
@RegisterForReflection(
    targets = {
      BuildSuccessful.class,
      BuildFailed.class,
      SoftwareRelease.class,
      SCMPublishCommit.class,
      EventEnvelope.class,
      EventFrame.class
    },
    classNames = "eu.wohlben.qits.eventstream.control.CanonicalJson$QitsEventMixin")
public final class EventWireReflection {

  private EventWireReflection() {}
}
