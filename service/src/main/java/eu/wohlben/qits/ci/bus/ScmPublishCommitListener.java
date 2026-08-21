package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.ci.control.CiEventSelectionEvaluator;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRunService;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The push end of the run queue: one {@code SCMPublishCommit} off the bus becomes one accepted run,
 * exactly as the post-receive POST used to.
 *
 * <p><b>It replaces {@code POST /ci/api/events/post-receive}</b>, which is gone. qits-githost used
 * to deliver {@code {repoId, branch, oldSha, newSha}} over HTTP, fire and forget: a delivery failure
 * was one debug line on the sender's side, and a qits-ci that was down, restarting or mid-cutover
 * when a push landed never learned about it — the loss the "replay the post-receive by hand" trick
 * existed for. The same push is now a durable event through the qits-eventstream outbox, so a
 * consumer that was away reads it back off the log.
 *
 * <p><b>The build is unchanged, deliberately.</b> {@link CiRunService#onPostReceive} is called with
 * the same values and does the same three things: validate, insert a {@code QUEUED} row, and
 * supersede any older queued push for the same {@code (repoId, branch)}. The row still carries
 * {@code POST_RECEIVE} as its trigger type.
 *
 * <h2>The repository arrives in two coordinate systems now</h2>
 *
 * <p>The event carries the git host's storage id and — filled from the address the push arrived on —
 * the public {@code projectId}/{@code repoName} pair. Both go onto the run row, and what the pair
 * buys is every URL the run builds afterwards: the clone address a step container is handed and the
 * content reads the config comes from. A push on the git host's internal id-addressed route carries
 * <b>no</b> pair, and neither does anything a pre-cutover host published; such a run is addressed by
 * id exactly as every run before this campaign was, which is correct rather than degraded — on that
 * platform the id IS the name. See {@link #repoOf}.
 *
 * <h2>The fifth value is the frame's id, and it closes the causation chain</h2>
 *
 * <p>It lands on {@code ci_run.trigger_event_id}, which {@code announceRun} reads back minutes later
 * to stamp the run's {@code BuildSuccessful} through {@code CausingEvent.parentOf}. Without it a
 * push run published a <b>root</b> event and the platform's chain broke in the middle: a release
 * causes a push, the push causes this event, and the deploy that follows the build could not be
 * traced back past it. It is carried as data on the row rather than in {@code CausationScope},
 * because that scope is a {@code ThreadLocal} and the publish happens on {@code ci-run-worker},
 * possibly after a restart — the same reason, and the same mechanism, the trigger engine uses.
 *
 * <p>It also puts push runs inside the unique constraint on {@code (trigger_event_id, repo_id,
 * config_path)}, which is a second guarantee rather than a cost: one announced push is one run, and
 * a duplicate is settled rather than built twice. The claim ledger already makes that unreachable
 * through this listener; the constraint sits underneath it on ci's own datasource.
 *
 * <h2>{@code suppressCi} is honoured HERE, because nobody honours it earlier any more</h2>
 *
 * <p>{@code -o qits.no-ci} used to be the git host's decision: the notifier read the push option and
 * skipped the POST, so qits-ci never heard of the push at all. The host no longer decides for its
 * consumers — the option is a FACT on the event, {@link SCMPublishCommit#suppressCi()}, and each
 * consumer says what it means to it. To a run engine it means <b>no run</b>: no row, nothing
 * superseded, nothing to cancel. The event is still consumed and settled, because a suppressed push
 * is handled rather than owed.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> whatever {@link CiRunService#onPostReceive} raises out of
 * its own transaction. A store that is down is a condition, not a verdict, so the claim rolls back
 * and the next catch-up sweep offers the push again.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not bind, and a payload whose
 * identifiers this service refuses ({@link BadRequestException} out of {@code CiIdentifiers} — a
 * repo id, branch or sha that could escape a path or an argv). Both are the same bytes on every
 * offer, so a throw would hold this consumer's watermark behind one push forever and the seam has no
 * dead letter. What was a 400 at the endpoint is a WARN and a settled event here: there is no caller
 * left to answer.
 *
 * <h2>Inline, on the dispatch thread</h2>
 *
 * <p>{@link #onFrame} runs inside the claiming transaction. That is right for this handler and
 * unlike {@code CiEventTriggerListener}: accepting a push is an insert in {@code CiRunService}'s own
 * transaction and then a submit to the run worker — no git-host read, no fan-out — so the claim
 * commits for work that has actually been recorded. The building happens on {@code ci-run-worker},
 * as it always did, and a {@code QUEUED} row survives a restart on its own.
 */
@ApplicationScoped
public class ScmPublishCommitListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ScmPublishCommitListener.class);

  /**
   * The storage key of this consumption: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} the push queue is caught up by. Not a label — it survives a rename of this
   * class, and it is never handed to a listener that means something else, since a consumer
   * inheriting it would inherit a watermark saying it had already built pushes it never saw.
   */
  static final String CONSUMER_ID = "ci-push-runs";

  @Inject CiRunService runService;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SCMPublishCommit.class.getSimpleName());
  }

  /**
   * How the push addresses its repository: the storage id the event has always carried, plus the
   * {@code projectId}/{@code repoName} the git host fills from the address the push arrived on.
   *
   * <p><b>Read off the payload TREE rather than the bound record</b>, and deliberately. The two
   * fields are new to {@code SCMPublishCommit} and this repository pins a vocabulary jar that may
   * predate them, so binding them would tie a run's identity to a version bump; walking the JSON
   * needs no reflection either, which is the rule the whole trigger engine follows for the
   * native-image reason {@code EventWireReflection} writes down. Absent — an id-addressed push, a
   * mirror sync, any event a pre-cutover git host published — yields a reference with no name, and
   * every URL built from it is the id-addressed one this service always built.
   */
  private static CiRepoRef repoOf(SCMPublishCommit push, String payload) {
    JsonNode root = CiEventSelectionEvaluator.parsePayload(payload);
    return CiRepoRef.of(push.repoId(), text(root, "projectId"), text(root, "repoName"));
  }

  /** One top-level string off the payload, or null when it is absent or not a string. */
  private static String text(JsonNode root, String field) {
    JsonNode at = root == null ? null : root.get(field);
    return at == null || !at.isTextual() ? null : at.textValue();
  }

  @Override
  public void onFrame(EventFrame frame) {
    SCMPublishCommit push;
    try {
      push = CanonicalJson.payloadTo(frame.payload(), SCMPublishCommit.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "SCMPublishCommit %s carried a payload this service cannot read (%s); it is settled"
              + " unbuilt",
          frame.id(), unreadable.toString());
      return;
    }
    if (push.suppressCi()) {
      // The push said "do not build this" and that is all this consumer has to do about it. Logged
      // at INFO because a build that deliberately did not happen is worth being able to find.
      LOG.infof(
          "%s@%s (%s) was pushed with qits.no-ci; no run recorded",
          push.repoId(), push.branch(), push.sha());
      return;
    }
    try {
      // The frame's id, never the payload's: SCMPublishCommit's own eventId is excluded from the
      // canonical payload by CanonicalJson's mix-in, so the instance bound above carries a freshly
      // minted one that qits-events never stored. Identity travels in the envelope, and the envelope
      // is the frame. Nor its parentId — the arriving event is what causes this run; its own parent
      // is the previous hop's business, which is the same rule CiEventTriggerListener follows.
      runService.onPostReceive(
          repoOf(push, frame.payload()),
          push.branch(),
          push.oldSha(),
          push.sha(),
          frame.id());
    } catch (BadRequestException refused) {
      // Poison: the identifiers are what they are on every offer, so no later attempt could accept
      // this push. Loud, because a git host publishing a ref name qits-ci refuses is a fact
      // somebody has to see, and silent, in the sense that nothing is retried for it.
      LOG.warnf(
          "SCMPublishCommit %s names something qits-ci refuses (%s); it is settled unbuilt",
          frame.id(), refused.getMessage());
    }
  }
}
