package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A push, as it arrives: one {@link EventFrame} carrying an {@link SCMPublishCommit} payload.
 *
 * <p>This is what every suite here used to build as a JSON body for {@code POST
 * /ci/api/events/post-receive}. The endpoint is gone, so a test drives {@code
 * ScmPublishCommitListener} instead — and the frame is built through {@link CanonicalJson} from a
 * <b>real</b> {@code SCMPublishCommit} rather than from a hand-written string, so what a test hands
 * the listener is byte for byte what qits-githost publishes. A field renamed on the record shows up
 * here as a compile error rather than as a suite that keeps passing against a payload nobody sends.
 *
 * <p>Shared across packages ({@code api} drives it too) rather than copied per test class, which is
 * the opposite of what this repo does with {@code FakeCiStepRunner} — those are duplicated because
 * the two MODULES do not share a test classpath, and everything here is one module.
 */
public final class ScmPushFrames {

  /** The all-zero sha git reports as the old id of a newly created branch. */
  public static final String ZERO_SHA = "0".repeat(40);

  private ScmPushFrames() {}

  /** An ordinary push: the branch moved, and CI is meant to build it. */
  public static EventFrame push(String repoId, String branch, String oldSha, String sha) {
    return frame(commit(repoId, branch, oldSha, sha, false));
  }

  /** The same push made with {@code -o qits.no-ci}: announced, and not to be built. */
  public static EventFrame suppressed(String repoId, String branch, String oldSha, String sha) {
    return frame(commit(repoId, branch, oldSha, sha, true));
  }

  /**
   * The record itself, with the head-commit metadata the HTTP event never carried filled in as a
   * real push would fill it. None of it reaches a run row today; it is here because a payload that
   * omitted it would not be the payload under test.
   */
  public static SCMPublishCommit commit(
      String repoId, String branch, String oldSha, String sha, boolean suppressCi) {
    Instant receivedAt = Instant.parse("2026-08-10T09:00:00Z");
    return new SCMPublishCommit(
        repoId,
        branch,
        oldSha,
        sha,
        ZERO_SHA.equals(oldSha) ? List.of() : List.of(oldSha),
        "A Pusher",
        "pusher@example.invalid",
        receivedAt,
        receivedAt,
        "a commit",
        suppressCi,
        receivedAt);
  }

  /**
   * A push that arrived on the <b>name-addressed</b> route: the same payload, plus the {@code
   * projectId}/{@code repoName} the git host fills in from the address.
   *
   * <p><b>The two fields are spliced into the JSON rather than set on the record</b>, and that is the
   * situation rather than a shortcut: this repository pins a {@code qits-githost-events} that
   * predates them, and qits-ci reads them off the payload tree for exactly that reason. A frame built
   * this way is byte-shaped like the one the current git host publishes, and it is what proves the
   * listener does not need the jar bumped to record a name.
   */
  public static EventFrame named(
      String repoId, String projectId, String repoName, String branch, String oldSha, String sha) {
    EventFrame plain = frame(commit(repoId, branch, oldSha, sha, false));
    try {
      ObjectMapper mapper = new ObjectMapper();
      ObjectNode payload = (ObjectNode) mapper.readTree(plain.payload());
      payload.put("projectId", projectId);
      payload.put("repoName", repoName);
      return new EventFrame(
          plain.id(),
          plain.name(),
          plain.occurredAt(),
          mapper.writeValueAsString(payload),
          plain.description(),
          plain.parentId());
    } catch (Exception e) {
      throw new IllegalStateException("could not build a name-addressed push frame", e);
    }
  }

  /** The envelope a publisher would have written, wrapped as the frame a consumer is handed. */
  public static EventFrame frame(SCMPublishCommit commit) {
    return new EventFrame(
        UUID.randomUUID().toString(),
        SCMPublishCommit.class.getSimpleName(),
        commit.occurredAt(),
        CanonicalJson.payload(commit),
        null,
        null);
  }
}
