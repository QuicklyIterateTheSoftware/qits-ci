package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.ReleaseJoin;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The four strings the release join is written against, checked against the wire form they have
 * to match.</b>
 *
 * <p>{@code ReleaseJoin} recognises a release by NAME and reads its key out of three payload FIELDS,
 * and all four are literals: {@code ci} depends on no other context's jar, deliberately, and
 * {@code ScmReleaseListener} walks the payload with {@code readTree} rather than binding it. That is
 * the right split and it leaves exactly one thing to go wrong: qits-workspaces renames the event or
 * a field, this service compiles, and the join silently stops closing — every release would then
 * publish without announcing, which reads exactly like a train that quietly did not roll.
 *
 * <h2>Why this is not {@link ScmPublishTagContractTest}'s mechanism</h2>
 *
 * <p>That test resolves its two strings against the real {@code SCMPublishTag}, because
 * {@code qits-githost-events} is a dependency of this module. The same move is not available here
 * and the reason is not a preference: <b>qits-workspaces publishes no vocabulary jar.</b> Measured
 * on 2026-08-12 against the platform Maven registry — it serves {@code qits-githost-events} and
 * {@code qits-eventstream} and answers {@code nothing is deployed} for
 * {@code eu/wohlben/qits/qits-workspaces-events}. A dependency on it would compile here from a
 * developer's {@code ~/.m2} and fail to resolve in the release pipeline's own step container, which
 * is a red release rather than a guard.
 *
 * <h2>So the TRANSCRIPTION below is the contract, and this file is where it is kept</h2>
 *
 * <p>{@link SCMRelease} is a hand-kept copy of the component list of
 * {@code services/qits-workspaces/workspaces-events/src/main/java/eu/wohlben/qits/workspaces/events/SCMRelease.java},
 * the record {@code services/qits-workspaces/service/…/bus/SCMReleaseAnnouncer.java} publishes. It
 * is <b>not</b> a fixture of expected JSON: the bytes are produced here by {@link CanonicalJson},
 * the same serializer the real publisher runs the real record through, so every rule about the wire
 * form — alphabetical keys, and the {@code QitsEvent} accessors the mix-in hides — stays the
 * library's rather than something this file guessed. What a person has to keep in step is one list
 * of component names, and that is the smallest honest surface for a contract two repositories share
 * and no jar carries.
 *
 * <p><b>Read that as the standing instruction it is:</b> a change to that record in qits-workspaces
 * is a change to this transcription, in the same campaign. A rename that lands there and not here
 * leaves this suite green and the join dead — which is the one failure this file cannot prevent and
 * is why it says so out loud.
 *
 * <p>The canonical bytes this produces are also what {@link DurableBusConsumptionTest} drives
 * through the real listener, so the strings are proved to work end to end rather than merely to be
 * present.
 */
public class ScmReleaseContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * A transcription of qits-workspaces' {@code SCMRelease} — its component list, its order and its
   * types, under the name it rides the bus under. See the class javadoc: this record IS the contract
   * this repository holds, and the file it copies is named there.
   *
   * <p><b>The NAME is transcribed too</b>, which is what makes the signature assertion below say
   * anything: {@code signature()} is the simple class name, so this record has to be spelled exactly
   * as qits-workspaces spells it. Only the shape matters otherwise, so the convenience constructor
   * the publisher uses is not copied; the canonical form is a function of the components and nothing
   * else.
   */
  record SCMRelease(
      UUID eventId,
      String projectId,
      String repository,
      String repositoryName,
      String branch,
      String version,
      Instant occurredAt)
      implements QitsEvent {}

  /** One release, as qits-workspaces would publish it. */
  static SCMRelease release(String repository, String repositoryName, String version) {
    return new SCMRelease(
        UUID.randomUUID(),
        "p-1",
        repository,
        repositoryName,
        "main",
        version,
        Instant.parse("2026-08-12T15:34:38Z"));
  }

  /** The canonical payload of one release — the bytes a frame carries. */
  static String canonicalPayload(String repository, String repositoryName, String version) {
    return CanonicalJson.payload(release(repository, repositoryName, version));
  }

  @Test
  public void theEventNameTheJoinMatchesIsTheOneThisEventRidesUnder() {
    // The signature is the simple class name — what ScmReleaseListener subscribes by, and what the
    // trigger engine compares a release recipe's `event:` against.
    assertEquals(SCMRelease.class.getSimpleName(), ReleaseJoin.RELEASE_EVENT_NAME);
  }

  @Test
  public void theThreeFieldsTheJoinReadsAreInTheCanonicalPayload() throws Exception {
    JsonNode payload =
        MAPPER.readTree(canonicalPayload("qits-ci", "qits-ci", "2026.812.153438"));

    assertTrue(
        payload.has(ScmReleaseListener.REPOSITORY_FIELD),
        "the canonical payload carries no " + ScmReleaseListener.REPOSITORY_FIELD);
    assertEquals("qits-ci", payload.get(ScmReleaseListener.REPOSITORY_FIELD).asText());

    assertTrue(
        payload.has(ScmReleaseListener.VERSION_FIELD),
        "the canonical payload carries no " + ScmReleaseListener.VERSION_FIELD);
    assertEquals("2026.812.153438", payload.get(ScmReleaseListener.VERSION_FIELD).asText());

    // The optional one: the join matches either spelling of the repository, so a rename of this
    // field costs the tolerance rather than the join. Still checked, because losing it silently is
    // how a tolerance becomes a comment about something that no longer exists.
    assertTrue(
        payload.has(ScmReleaseListener.REPOSITORY_NAME_FIELD),
        "the canonical payload carries no " + ScmReleaseListener.REPOSITORY_NAME_FIELD);
  }

  /**
   * The two the payload must NOT carry, which is why the listener reads them off the frame instead.
   *
   * <p>{@code eventId} and {@code occurredAt} are {@code QitsEvent}'s own accessors and the
   * canonical mix-in hides every one of them by signature — identity and time travel in the
   * envelope. A payload that started carrying them would not break the join, but it would mean the
   * library's wire contract had changed under this service, and the listener's use of {@code
   * frame.id()} and {@code frame.occurredAt()} would be reading the wrong one of two.
   */
  @Test
  public void theEnvelopesOwnFieldsAreNotInThePayload() throws Exception {
    JsonNode payload =
        MAPPER.readTree(canonicalPayload("qits-ci", "qits-ci", "2026.812.153438"));

    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
    assertFalse(payload.has("occurredAt"), "and so does the timestamp");
  }
}
