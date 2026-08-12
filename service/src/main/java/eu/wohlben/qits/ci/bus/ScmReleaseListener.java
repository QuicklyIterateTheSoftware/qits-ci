package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.ReleaseJoin;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The bus end of the release join: consumes {@code SCMRelease} and records that a {@code
 * (repository, version)} really was released, then makes whatever announcements that unblocks.
 *
 * <p><b>Why this listener exists at all.</b> qits-ci used to announce a {@code SoftwareRelease} for
 * every green release-recipe run whatever triggered it, so a bootstrap replay — which restores a
 * release tag and nothing else — impersonated a release and woke the train against a half-deployed
 * platform. Only qits-workspaces publishes {@code SCMRelease}, and only a real release produces one,
 * so this is the fact that tells the two apart. {@link ReleaseJoin} is the rule; this bean is the
 * arrival.
 *
 * <p>It is the same shape as the three listeners beside it and adapts inward like {@code
 * CiEventTriggerListener} does: an {@link EventFrame} becomes five plain strings and an instant, so
 * the {@code ci} module keeps importing no publish/subscribe type.
 *
 * <h2>What it reads, and why by name</h2>
 *
 * <p>{@code repository}, {@code repositoryName} and {@code version} are read out of the payload with
 * a {@code readTree} walk, the trigger engine's own precedent — no binding, so no native-image
 * reflection metadata and no dependency on qits-workspaces' vocabulary jar, which is on neither
 * module's classpath here. That is also the one guard this path does not have: unlike {@code
 * SCMPublishTag}, whose field names {@code ScmPublishTagContractTest} resolves against the real
 * record, a rename over there would stop the join closing rather than fail a build here.
 *
 * <h2>Inline, in the claiming transaction</h2>
 *
 * <p>{@link #onFrame} runs on the bus's websocket worker or on the catch-up sweeper's thread, and
 * the fact row is written there — it is the effect the claim is a claim <em>of</em>, and handing it
 * to a queue would commit the claim for work that had not happened. The announcements it unblocks
 * are published from the same call, which means a bus publish on the dispatch thread. That is
 * bounded by the publish timeout and it is the honest place for it: an unreachable qits-events is
 * also the reason no frame would be arriving on this thread.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> anything the join raises out of its own database work. A
 * store that is down is a condition rather than a verdict, so the claim rolls back and the event
 * stays owed for the next sweep — which is exactly right here, because a lost {@code SCMRelease} is
 * a release the platform never hears about.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not parse, and one carrying no
 * repository or no version. Neither can succeed on a later offer — the same bytes fail identically
 * every time — and a throw would hold this consumer's watermark behind one bad event forever. A
 * frame with no {@code occurredAt} is <b>not</b> poison: the join orders nothing by it, so the
 * frame's absence costs the row a timestamp and never the fact, and the seen-at stamp is this
 * instance's own.
 */
@ApplicationScoped
public class ScmReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ScmReleaseListener.class);

  /**
   * The storage key of this consumption: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} the join is caught up by. Not a label — it survives a rename of this class,
   * and it is never handed to a listener that means something else.
   */
  static final String CONSUMER_ID = "ci-release-facts";

  /** No reflection needed — a plain read of already-parsed platform data, {@code
   *  DaemonReleaseListener}'s own precedent. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final String REPOSITORY_FIELD = "repository";
  static final String REPOSITORY_NAME_FIELD = "repositoryName";
  static final String VERSION_FIELD = "version";

  @Inject ReleaseJoin join;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(ReleaseJoin.RELEASE_EVENT_NAME);
  }

  @Override
  public void onFrame(EventFrame frame) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(frame.payload());
    } catch (Exception unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload, so it is settled unrecorded: %s",
          ReleaseJoin.RELEASE_EVENT_NAME, frame.id(), unreadable.toString());
      return;
    }
    String repository = text(payload, REPOSITORY_FIELD);
    String version = text(payload, VERSION_FIELD);
    if (repository == null || version == null) {
      // Poison: the join's key is exactly these two, so nothing about this event can ever close it.
      LOG.warnf(
          "%s %s names no '%s' or no '%s'; there is no release fact to record",
          ReleaseJoin.RELEASE_EVENT_NAME, frame.id(), REPOSITORY_FIELD, VERSION_FIELD);
      return;
    }
    join.onScmRelease(
        repository,
        text(payload, REPOSITORY_NAME_FIELD),
        version,
        frame.id(),
        // The event's own timestamp when it has one, this instance's clock otherwise. Nothing here
        // orders by it, so a missing one is a poorer row rather than an unusable one.
        frame.occurredAt() == null ? java.time.Instant.now() : frame.occurredAt());
  }

  /** One payload field as a non-blank string, or null. */
  private static String text(JsonNode payload, String field) {
    String value = payload.path(field).asText(null);
    return value == null || value.isBlank() ? null : value;
  }
}
