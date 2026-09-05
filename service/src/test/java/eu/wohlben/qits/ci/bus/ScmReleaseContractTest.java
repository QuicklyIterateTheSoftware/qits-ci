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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The four strings the release join is written against, checked against the wire form they have
 * to match.</b>
 *
 * <p>{@code ReleaseJoin} recognises a release by NAME and reads its key out of three payload FIELDS,
 * and all four are literals: {@code ci} depends on no other context's jar, deliberately, and
 * {@code ScmReleaseListener} walks the payload with {@code readTree} rather than binding it. That is
 * the right split and it leaves exactly one thing to go wrong: the publisher renames the event or a
 * field, this service compiles, and the join silently stops closing — every release would then
 * publish without announcing, which reads exactly like a train that quietly did not roll.
 *
 * <h2>The publisher moved and this file did not have to</h2>
 *
 * <p>{@code SCMRelease} is published by <b>qits-projects</b> now, not by qits-workspaces: the release
 * flow's rearchitecture puts the release request — its sources, its backing branch, its tag — in
 * qits-projects, and the announcement belongs to whoever creates the tag. The event's shape did not
 * move with it. Same signature, same seven components, same five payload fields, so the transcription
 * below was unchanged and every consuming line in this service was unchanged with it.
 *
 * <h2>The one field that HAS been added since, and why this file is where you find out</h2>
 *
 * <p><b>{@code commitSha}</b> — what the tag points at. It is the standing instruction below being
 * carried out for the first time: qits-projects grew the component, so this transcription grew it
 * in the same campaign, and this repository's own {@code .config/qits/ci-event-release.yml} spends
 * it on a {@code checkout:} so a release run is anchored at the released tag instead of at {@code
 * main}. Strictly additive — the five original fields keep their names, their types and their order,
 * and the new one is omitted from the payload when it is absent — so nothing that reads the old
 * shape had to change, and the pipeline that reads the new one has a fallback for the old.
 *
 * <p><b>That nothing had to change is the finding, and it is a property of how this consumer is
 * written rather than luck.</b> Nothing here keys on the producer: the durable seam subscribes by
 * SIGNATURE, {@code EventFrame} carries no publisher and no source service, {@code ReleaseJoin}'s key
 * is {@code (repository, version)}, and the causation chain is the frame's own id rather than an
 * expectation about which service minted it. A consumer that had recorded "qits-workspaces said so"
 * anywhere — a producer filter, a source-service check, an expected parent — would have gone silently
 * dead on the cutover, in the direction that publishes without announcing.
 *
 * <p><b>What DID change is one value, and this file pins it too</b> — see {@link
 * #aReleaseCutFromABackingBranchIsTheSameContract}. {@code branch} used to be a source branch that
 * still existed; it is now {@code release/<requestId>}, the request's backing branch, and that branch
 * is <em>deleted</em> at tag creation. No field this service reads is affected, which is the whole
 * assertion.
 *
 * <h2>Why this is not {@link ScmPublishTagContractTest}'s mechanism</h2>
 *
 * <p>That test resolves its two strings against the real {@code SCMPublishTag}, because
 * {@code qits-githost-events} is a dependency of this module. The same move is not available here
 * and the reason is not a preference: <b>neither publisher of this event ships a vocabulary jar.</b>
 * Measured on 2026-08-12 against the platform Maven registry — it serves {@code qits-githost-events}
 * and {@code qits-eventstream} and answers {@code nothing is deployed} for
 * {@code eu/wohlben/qits/qits-workspaces-events}. qits-projects, which publishes it now, is the same
 * answer by its own ruling: its one published event lives in its {@code service/…/bus/} package and a
 * consumer decodes it into a local record, which is this platform's standing pattern. A dependency on
 * either would compile here from a developer's {@code ~/.m2} and fail to resolve in the release
 * pipeline's own step container, which is a red release rather than a guard.
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
   * A transcription of the published {@code SCMRelease} — its component list, its order and its
   * types, under the name it rides the bus under. See the class javadoc: this record IS the contract
   * this repository holds, and the file it copies is named below.
   *
   * <p>The source is {@code components/qits-projects/qits-projects-service/service/src/main/java/
   * eu/wohlben/qits/projects/bus/SCMRelease.java} as of the release-flow rearchitecture; it was
   * qits-workspaces' {@code workspaces-events/…/SCMRelease.java} before, and the two are
   * component-for-component identical, which is why the move cost this transcription nothing.
   *
   * <p><b>The NAME is transcribed too</b>, which is what makes the signature assertion below say
   * anything: {@code signature()} is the simple class name, so this record has to be spelled exactly
   * as the publisher spells it. Only the shape matters otherwise, so the convenience constructor the
   * publisher uses is not copied; the canonical form is a function of the components and nothing
   * else.
   */
  record SCMRelease(
      UUID eventId,
      String projectId,
      String repository,
      String repositoryName,
      String branch,
      String version,
      String commitSha,
      Instant occurredAt)
      implements QitsEvent {}

  /**
   * One release, as qits-projects publishes it: the branch is the release request's backing branch,
   * which is the only value the publisher move changed and the one this repository has to be
   * indifferent to.
   */
  static SCMRelease release(String repository, String repositoryName, String version) {
    return release(repository, repositoryName, version, BACKING_BRANCH);
  }

  /** The same, with the branch stated — for the two cases that are about the branch. */
  static SCMRelease release(
      String repository, String repositoryName, String version, String branch) {
    return release(repository, repositoryName, version, branch, RELEASED_SHA);
  }

  /** The same, with the commit stated — for the cases that are about the tag's own commit. */
  static SCMRelease release(
      String repository, String repositoryName, String version, String branch, String commitSha) {
    return new SCMRelease(
        UUID.randomUUID(),
        "p-1",
        repository,
        repositoryName,
        branch,
        version,
        commitSha,
        Instant.parse("2026-08-12T15:34:38Z"));
  }

  /**
   * What the tag points at: the version-bump commit qits-projects made on the backing branch, which
   * is the coordinate the release pipeline's {@code checkout:} anchors on.
   */
  static final String RELEASED_SHA = "71663ccdceb65ce46f4cf44c8cb3a016de5ff6af";

  /**
   * A release request's backing branch: {@code release/<requestId>}. Written by the git host's merge
   * primitive, never pushed by anybody, and <b>deleted at tag creation</b> — so by the time a frame
   * carrying it reaches this service the ref does not exist.
   */
  static final String BACKING_BRANCH = "release/9f2c1a7e-4b31-4c8e-9a11-6d0f5c2e8b44";

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
   * The publisher move's one behavioural change, pinned: {@code branch} is now a ref that no longer
   * exists, and every field the join reads is byte-identical either way.
   *
   * <p>qits-projects folds a release request's sources onto {@code release/<id>}, tags it, and
   * <b>deletes the source and backing branches in the same operation</b>. So the branch on this event
   * is dead on arrival, and a consumer that had used it — to clone, to look a ref up, to validate an
   * identifier — would fail on every release of the new flow. This service uses it nowhere: the join
   * key is {@code (repository, version)}, and the release pipeline's run builds the tracked branch
   * and checks the TAG out inside its own step. The assertion is the payload with the two branches
   * differing in exactly one field.
   */
  @Test
  public void aReleaseCutFromABackingBranchIsTheSameContract() throws Exception {
    JsonNode fromBacking =
        MAPPER.readTree(CanonicalJson.payload(release("qits-ci", "qits-ci", "2026.812.153438")));
    JsonNode fromMain =
        MAPPER.readTree(
            CanonicalJson.payload(release("qits-ci", "qits-ci", "2026.812.153438", "main")));

    assertEquals(BACKING_BRANCH, fromBacking.get("branch").asText(), "the branch nobody pushed");
    for (String field :
        List.of(
            ScmReleaseListener.REPOSITORY_FIELD,
            ScmReleaseListener.REPOSITORY_NAME_FIELD,
            ScmReleaseListener.VERSION_FIELD)) {
      assertEquals(
          fromMain.get(field),
          fromBacking.get(field),
          field + " differs with the branch, so this service is not indifferent to it after all");
    }
  }

  /**
   * <b>The sixth field, and the one this repository's release pipeline is now anchored on.</b>
   *
   * <p>{@code commitSha} is what the tag points at — the version-bump commit qits-projects made on
   * the backing branch. Until it existed, an {@code SCMRelease} was the one release statement on the
   * platform that could not be checked out: {@code branch} names a ref the tagging operation
   * deletes, and {@code version} names a tag and not a commit. So {@code ci-event-release.yml} had
   * to declare no {@code checkout:}, be recorded at {@code main}, and go fetch {@code
   * refs/tags/$version} inside its own step — every release run displaying as {@code main@<head>},
   * a run recorded against a commit it did not build.
   *
   * <p>With this field the recipe declares {@code checkout: { branch: version, sha: commitSha }} and
   * the trigger engine resolves both out of the payload. The two names below are therefore <b>dot
   * paths in a committed file</b>, exactly as {@code ReleaseJoin}'s three field names are: a rename
   * over there is a release pipeline that stops anchoring, so it is asserted here beside them.
   */
  @Test
  public void theTagAnchorsTheReleaseRecipeResolvesAreInTheCanonicalPayload() throws Exception {
    JsonNode payload =
        MAPPER.readTree(canonicalPayload("qits-ci", "qits-ci", "2026.812.153438"));

    assertTrue(payload.has("version"), "the recipe's checkout.branch path resolves nothing");
    assertEquals("2026.812.153438", payload.get("version").asText(), "the tag's own name");
    assertTrue(payload.has("commitSha"), "the recipe's checkout.sha path resolves nothing");
    assertEquals(RELEASED_SHA, payload.get("commitSha").asText(), "what the tag points at");
  }

  /**
   * <b>The compatibility arm, at the wire.</b> The field is additive, so a release published before
   * it existed — a replay out of the durable log, an older publisher, a rolled-back one — carries no
   * {@code commitSha} KEY at all rather than a null: {@code CanonicalJson} serializes with {@code
   * NON_NULL} inclusion. That absence is precisely what {@code ci-event-release.yml}'s {@code
   * checkout.optional: true} is written against, and the engine's fallback is pinned in {@code
   * CiEventCheckoutTest}; this is the same claim at the other end, that such a payload is otherwise
   * byte-identical to today's and every field the join reads is untouched.
   */
  @Test
  public void aReleaseFromBeforeTheCommitShaCarriesNoSuchKeyAndIsOtherwiseIdentical()
      throws Exception {
    JsonNode withSha =
        MAPPER.readTree(canonicalPayload("qits-ci", "qits-ci", "2026.812.153438"));
    JsonNode without =
        MAPPER.readTree(
            CanonicalJson.payload(
                release("qits-ci", "qits-ci", "2026.812.153438", BACKING_BRANCH, null)));

    assertFalse(
        without.has("commitSha"),
        "NON_NULL: an absent commit is an absent key, which is what the optional checkout resolves"
            + " to nothing against — a null would resolve and then fail validation");
    for (String field :
        List.of(
            ScmReleaseListener.REPOSITORY_FIELD,
            ScmReleaseListener.REPOSITORY_NAME_FIELD,
            ScmReleaseListener.VERSION_FIELD,
            "branch")) {
      assertEquals(
          withSha.get(field),
          without.get(field),
          field + " moved with commitSha, so the addition was not additive after all");
    }
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
