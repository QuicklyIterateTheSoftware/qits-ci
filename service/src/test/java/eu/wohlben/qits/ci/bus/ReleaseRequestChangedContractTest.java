package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>The four strings a repository's QA pipeline is written against, checked against the wire form
 * they have to match.</b>
 *
 * <p>qits-projects maintains one backing branch per open release request — {@code release/<id>}, an
 * octopus merge of N sources — and publishes {@code ReleaseRequestChanged} on every successful
 * re-fold. That branch is written by the git host's merge primitive, which fires no {@code
 * post-receive} and therefore publishes no {@code SCMPublishCommit}: without this event the fold
 * exists and <b>nothing builds it</b>. So a repository's single QA pipeline selects this event and
 * checks the fold out:
 *
 * <pre>{@code
 * event: ReleaseRequestChanged
 * when:
 *   - repoName: { exact: <this repository> }
 * checkout:
 *   branch: backingBranch
 *   sha: mergedSha
 * }</pre>
 *
 * <p>Four literals therefore have to agree with a record in another repository: the event NAME
 * ({@link CiRunService#RELEASE_REQUEST_EVENT_NAME}), the two checkout dot-paths, and the field the
 * run's {@code release_request_id} column is read out of ({@link
 * CiRunService#RELEASE_REQUEST_ID_FIELD}). Nothing in this service binds the payload — the trigger
 * engine subscribes to {@code "*"} and walks a {@code JsonNode} — so nothing but this file would
 * notice a rename.
 *
 * <h2>Why this is a transcription and not a resolution against the real record</h2>
 *
 * <p>{@code ScmPublishTagContractTest} resolves its two strings against the real {@code
 * SCMPublishTag}, because {@code qits-githost-events} is a dependency of this module. That move is
 * not available here for the reason {@code ScmReleaseContractTest} states at length about
 * qits-workspaces: <b>qits-projects publishes no vocabulary jar</b>, deliberately and by its own
 * ruling — a jar the platform's Maven registry does not serve resolves from a developer's {@code
 * ~/.m2} and fails inside a release pipeline's own step container, which is a red release rather
 * than a guard. That repository's own note says the field list below "is the contract qits-ci builds
 * its local record against"; this is that record.
 *
 * <p>So the transcription below is the contract, and it is <b>not</b> a fixture of expected JSON:
 * the bytes are produced by {@link CanonicalJson}, the same serializer the real publisher runs the
 * real record through, so every rule about the wire form stays the library's. What a person keeps in
 * step is one list of component names.
 *
 * <p><b>Read that as the standing instruction it is:</b> a change to {@code
 * qits-projects-service/service/…/bus/ReleaseRequestChanged.java} is a change to this transcription,
 * in the same campaign. A rename that lands there and not here leaves this suite green and every
 * repository's release-request pipeline silently dead — which is the one failure this file cannot
 * prevent and is why it says so out loud.
 */
public class ReleaseRequestChangedContractTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The dot-path a QA pipeline's {@code checkout:} names for the branch to clone. */
  static final String BACKING_BRANCH_FIELD = "backingBranch";

  /** The dot-path a QA pipeline's {@code checkout:} names for the commit to build. */
  static final String MERGED_SHA_FIELD = "mergedSha";

  /**
   * A transcription of qits-projects' {@code ReleaseRequestChanged} — its component list, its order
   * and its types, under the name it rides the bus under.
   *
   * <p><b>The NAME is transcribed too</b>, which is what makes the signature assertion below say
   * anything: a wire signature is the simple class name, so this record has to be spelled exactly as
   * qits-projects spells it. Only the shape matters otherwise, so the convenience constructor the
   * publisher uses is not copied; the canonical form is a function of the components and nothing
   * else.
   */
  record ReleaseRequestChanged(
      UUID eventId,
      String projectId,
      String repoId,
      String repoName,
      String releaseRequestId,
      String backingBranch,
      String mergedSha,
      Instant changedAt)
      implements QitsEvent {

    @Override
    public Instant occurredAt() {
      return changedAt;
    }
  }

  /** One re-fold, as qits-projects would publish it. */
  static ReleaseRequestChanged changed(String repoId, String requestId, String mergedSha) {
    return new ReleaseRequestChanged(
        UUID.randomUUID(),
        "qits",
        repoId,
        "qits-ci-service",
        requestId,
        "release/" + requestId,
        mergedSha,
        Instant.parse("2026-09-03T09:07:06Z"));
  }

  /** The canonical payload of one re-fold — the bytes a frame carries. */
  static String canonicalPayload(String repoId, String requestId, String mergedSha) {
    return CanonicalJson.payload(changed(repoId, requestId, mergedSha));
  }

  @Test
  public void theEventNameACiPipelineSelectsIsTheOneThisEventRidesUnder() {
    assertEquals(
        ReleaseRequestChanged.class.getSimpleName(), CiRunService.RELEASE_REQUEST_EVENT_NAME);
  }

  @Test
  public void theThreeFieldsAQaPipelineDependsOnAreInTheCanonicalPayload() throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    // The two a `checkout:` names. Without both there is no truthful (branch, sha) pair and the
    // engine records no run at all — the fold would exist and nothing would build it.
    assertTrue(
        payload.has(BACKING_BRANCH_FIELD),
        "the canonical payload carries no " + BACKING_BRANCH_FIELD);
    assertEquals("release/rr-42", payload.get(BACKING_BRANCH_FIELD).asText());
    assertTrue(payload.has(MERGED_SHA_FIELD), "the canonical payload carries no " + MERGED_SHA_FIELD);
    assertEquals("c".repeat(40), payload.get(MERGED_SHA_FIELD).asText());

    // The one the run row's release_request_id is read out of. Losing it costs no run — it costs
    // every run of every release request its only stable handle, which is what cancel and retry
    // address work by.
    assertTrue(
        payload.has(CiRunService.RELEASE_REQUEST_ID_FIELD),
        "the canonical payload carries no " + CiRunService.RELEASE_REQUEST_ID_FIELD);
    assertEquals("rr-42", payload.get(CiRunService.RELEASE_REQUEST_ID_FIELD).asText());
  }

  @Test
  public void theRepositoryASelectionMatchesOnIsInTheCanonicalPayload() throws Exception {
    // `when: - repoName: { exact: … }` is what makes this event one repository's rather than the
    // whole catalogue's, and the engine's addressable alias reads repoName first and repoId after.
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    assertEquals("qits-ci-service", payload.get("repoName").asText());
    assertEquals("r-1", payload.get("repoId").asText());
  }

  /**
   * The two the payload must NOT carry. {@code eventId} and {@code occurredAt} are {@code
   * QitsEvent}'s own accessors and the canonical mix-in hides every one of them by signature —
   * identity and time travel in the envelope, which is where the trigger engine reads them from.
   * Note {@code changedAt} IS in the payload and is also the envelope's {@code occurredAt}: the
   * record's own component keeps its name.
   */
  @Test
  public void theEnvelopesOwnFieldsAreNotInThePayload() throws Exception {
    JsonNode payload = MAPPER.readTree(canonicalPayload("r-1", "rr-42", "c".repeat(40)));

    assertFalse(payload.has("eventId"), "identity travels in the envelope, never in the payload");
    assertFalse(payload.has("occurredAt"), "and so does the timestamp");
    assertTrue(payload.has("changedAt"), "the record's own timestamp component stays a field");
  }
}
