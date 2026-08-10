package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiArtifact;
import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.DaemonReleaseLog;
import eu.wohlben.qits.ci.events.SoftwareRelease;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The bus end of the daemon pin ladder (ci-daemon-autoadopt-plan.md, workstream BX): consumes
 * {@code SoftwareRelease}, adopts the ones that name {@code qits-ci-daemon}, and seeds the ladder
 * at startup from what a restart missed.
 *
 * <h2>The frame, not the typed object -- measured rather than assumed</h2>
 *
 * <p>The plan's §2.8 calls this a {@code QitsEventListener<SoftwareRelease>}. It cannot be one and
 * still work: {@code CanonicalJson}'s mix-in hides every method {@link
 * eu.wohlben.qits.eventstream.QitsEvent} declares, by <b>signature</b>, from every implementation
 * uniformly -- and unlike {@code BuildSuccessful} (whose payload timestamp is the differently-named
 * {@code finishedAt}, with {@code occurredAt()} overridden to forward to it), {@link
 * SoftwareRelease#occurredAt()} <b>is</b> the interface method, so it is excluded from the payload
 * exactly like {@code eventId}. A typed listener's {@code onEvent} therefore receives a {@code
 * SoftwareRelease} rebuilt from the payload alone: {@code eventId} is fresh and unrelated to the id
 * qits-events actually stored, and {@code occurredAt} is bluntly {@code null} -- measured by running
 * exactly that path in this suite before writing this class, where it threw {@code
 * CiDaemonPins.adopt}'s own {@code IllegalArgumentException("occurredAt is required")}. Both are
 * exactly what {@link CiDaemonPins#adopt} needs: the real event id, for the idempotency key a
 * redelivery relies on, and the real {@code occurredAt}, for the freshness ordering that keeps a
 * late-delivered older release from being treated as a demotion (§2.6). Neither is recoverable from
 * the typed object.
 *
 * <p>{@link EventFrame} carries both, verbatim, because it <b>is</b> the envelope -- which is what
 * every seam but the typed one hands over.
 *
 * <h2>Durable, and the freshness guard is what makes that safe</h2>
 *
 * <p>This was a {@code QitsRawEventListener}: live-only, so a daemon release published while this
 * process was down was never adopted at all, and the only cure was {@link #reconcileFromLog}'s
 * startup read. As a {@link QitsDurableEventListener} the catch-up sweep covers that window
 * generally, and one event is adopted exactly once whatever mix of live frame, catch-up row and
 * startup reconciliation produced the arrivals.
 *
 * <p><b>Catch-up delivers late and out of order, and this ladder is last-writer-wins</b>, so the tip
 * check the seam demands is not optional here -- adopting a release that is older than the one
 * already pinned would roll the daemon backwards. It is already written and now load-bearing:
 * {@link CiDaemonPins#adopt} refuses any candidate whose {@code occurredAt} is not after the newest
 * adopted one (§2.6), and refuses a second adoption of the same event id. Both were written for a
 * redelivery on the live stream; both are exactly what a minutes-old caught-up frame needs.
 *
 * <p>Unlike the trigger engine, this listener's interest <em>is</em> knowable at startup -- one event
 * name -- so {@link #signatures()} names it, and {@link #selects} narrows further to the one daemon
 * this ladder adopts for. That narrowing is a pure read of the payload, which is what the seam asks
 * of a predicate, and it is what keeps the claim ledger holding daemon releases rather than every
 * {@code SoftwareRelease} the platform has ever published.
 *
 * <h2>Adoption is inline; only the probe owns a thread</h2>
 *
 * <p>{@link #onFrame} runs inside the claiming transaction, on the bus's websocket worker or on the
 * sweeper's thread. Adoption is a fast upsert and belongs there: it is the effect the claim is a
 * claim <em>of</em>, and enqueueing it would commit the claim for work that had not happened.
 *
 * <p>The probe cannot go there. {@link CiDaemonPins#answer()} may launch a container -- ten to thirty
 * seconds, mostly an image pull the first time -- and ci-daemon-autoadopt-plan.md §2.4 asks for it
 * asynchronously the moment a candidate lands, so {@code pinDaemon()} never pays for the first run's
 * probe. On the dispatch thread it would stall every other consumer, and inside the claiming
 * transaction it would hold a database connection across a container pull. Not {@code
 * ci-trigger-worker} either -- that thread's job is evaluating trigger files against candidate
 * repositories, an unrelated latency class, and sharing it would let a slow probe delay an
 * event-triggered build's own evaluation. A third single-threaded queue, sized like the trigger
 * engine's: bounded, so a burst costs a WARN naming the event rather than heap. A full queue is not a
 * failure of the adoption -- probing is lazy by design, so the next {@code answer()} does it.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p><b>Retryable, and left to throw:</b> anything {@link CiDaemonPins#adopt} raises out of its own
 * transaction. A database that is down is a condition, not a verdict, so the claim rolls back and the
 * event stays owed for the next sweep.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not parse and a frame with no
 * {@code occurredAt}. Neither can succeed on a later offer -- they are the same bytes every time --
 * and a throw would hold this consumer's watermark behind one bad event forever. The unparseable
 * payload is answered in {@link #selects}, which settles it with no row at all; a missing {@code
 * occurredAt} is answered in {@link #onFrame}, because it is the one thing the ladder cannot order by.
 */
@ApplicationScoped
public class DaemonReleaseListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(DaemonReleaseListener.class);

  /**
   * The storage key of this consumption: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} the ladder is caught up by. Not a label -- it survives a rename of this
   * class, and it is never handed to a listener that means something else, since a consumer
   * inheriting it would inherit a watermark saying it had already adopted releases it never saw.
   */
  static final String CONSUMER_ID = "ci-daemon-adopt";

  /** No reflection needed -- a plain read of already-parsed platform data, the trigger engine's own
   *  precedent ({@code CiEventSelectionEvaluator}). */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Deep enough that reaching it means something is wrong rather than something is busy. */
  static final int QUEUE_CAPACITY = 256;

  @Inject CiDaemonPins pins;

  /** Zero implementations is supported (the {@code RunAnnouncer} precedent) -- startup discovery
   *  simply never reconciles, and the durable table is whatever a live event already adopted. */
  @Inject Instance<DaemonReleaseLog> releaseLog;

  @ConfigProperty(name = "qits.ci.daemon-autoadopt-enabled")
  boolean autoadoptEnabled;

  private final ThreadPoolExecutor adopter =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          r -> {
            Thread t = new Thread(r, "ci-daemon-adopt-worker");
            t.setDaemon(true);
            return t;
          });

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SoftwareRelease.class.getSimpleName());
  }

  /**
   * The one daemon this ladder adopts releases for (§1.1's "out of scope"), decided from the payload
   * alone -- a pure read, which is what the seam asks of a predicate, and the reason a {@code
   * SoftwareRelease} for anything else leaves no claim row behind it.
   *
   * <p>An unreadable payload answers <b>no</b> rather than throwing. The seam treats a throw here as
   * a failure and keeps offering the event, which is right for a predicate that could not be
   * evaluated -- and wrong for one that will read the same bytes and fail identically forever.
   */
  @Override
  public boolean selects(EventFrame frame) {
    return daemonVersionOf(frame) != null;
  }

  /**
   * Adopts the release and hands the probe to {@code ci-daemon-adopt-worker}. Runs inside the
   * claiming transaction -- see the class javadoc on why the upsert belongs here and the probe does
   * not, and on which failures are left to throw.
   */
  @Override
  public void onFrame(EventFrame frame) {
    String version = daemonVersionOf(frame);
    if (version == null) {
      // selects() said yes a moment ago on the same immutable frame, so this is unreachable. Cheaper
      // than an assertion and it cannot wedge anything.
      return;
    }
    if (frame.occurredAt() == null) {
      // Poison: the ladder orders candidates by occurredAt and has nothing else to order by, so no
      // later offer of this frame could be adopted either. adopt() would throw, and a throw is
      // retried forever.
      LOG.warnf(
          "SoftwareRelease %s carries no occurredAt; daemon release %s cannot be ordered against the"
              + " ladder and is settled unadopted",
          frame.id(), version);
      return;
    }
    pins.adopt(version, frame.id(), frame.occurredAt());
    try {
      // The async probe-on-adoption ci-daemon-autoadopt-plan.md §2.4 asks for: answer() walks the
      // ladder and probes any UNPROVEN rung it passes, persisting the verdict before returning. On
      // this dedicated thread that costs nobody else anything, and it is what lets pinDaemon() find
      // an already-proven top rung on the very next run rather than paying for the first one's probe.
      adopter.execute(pins::answer);
    } catch (RejectedExecutionException full) {
      // The adoption stands; only the eager probe is lost, and probing is lazy anyway. Failing the
      // handler here would roll the claim back and re-adopt an already-adopted release.
      LOG.warnf(
          "Daemon adoption queue is full -- release %s (eventId=%s) was adopted but not probed",
          version, frame.id());
    }
  }

  /**
   * The version this frame releases for {@code qits-ci-daemon}, or null when it releases something
   * else or cannot be read at all. Asked twice per event -- once by {@link #selects}, once by {@link
   * #onFrame} -- because a predicate the seam calls separately cannot hand state forward, and one
   * more {@code readTree} of an already-in-memory string is cheaper than a per-frame cache that could
   * disagree with itself.
   */
  private static String daemonVersionOf(EventFrame frame) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(frame.payload());
    } catch (Exception e) {
      LOG.warnf("SoftwareRelease %s carried an unreadable payload: %s", frame.id(), e.toString());
      return null;
    }
    String packageType = payload.path("packageType").asText(null);
    String packageName = payload.path("packageName").asText(null);
    String version = payload.path("version").asText(null);
    if (!CiArtifact.Type.DAEMON.declared().equals(packageType)
        || !CiDaemonPins.DAEMON_NAME.equals(packageName)
        || version == null
        || version.isBlank()) {
      return null;
    }
    return version;
  }

  /**
   * Seeds the ladder with what a restart missed: the newest {@code limit=2} daemon releases, read
   * off the event log through {@link DaemonReleaseLog} (§1.6, §2.8) -- the same reconciliation a
   * live event takes, just for the releases that happened while nothing was listening.
   *
   * <p><b>Adopted oldest first, though the log answers newest first.</b> {@link CiDaemonPins#adopt}
   * refuses a candidate whose {@code occurredAt} is not after the newest one already adopted --
   * exactly the guard that makes a late-delivered older release inert (§2.6) -- so adopting
   * newest-then-older on a fresh table would have the second call refuse itself as "not newer". This
   * is not that case: both belong in the table, as rung one and rung two, so the older of the two
   * has to land first.
   *
   * <p><b>Catch-up did not make this redundant, and the difference is the watermark.</b> A durable
   * consumer that has never run initializes at the <em>head</em> of the log and replays nothing --
   * consume-from-now, the seam's default and the right one. So a fresh deployment with an empty
   * ladder would sit on the configured pin until the next daemon release happened. This read is what
   * seeds the two rungs it should already have had. What the sweep owns instead is the ordinary case:
   * a process that has consumed before and was away for a while.
   *
   * <p>The two cannot double-adopt. Adoption is keyed on the event id, so a release this read already
   * took is refused when the sweep or the stream offers it later.
   *
   * <p>Guarded by {@code qits.ci.daemon-autoadopt-enabled} alone, not {@code
   * qits.eventstream.enabled} -- this is a plain HTTP call the bus's darkness does not cover (§2.9),
   * and the {@code %dev}/{@code %test} default for this key is {@code false} for exactly that reason.
   *
   * <p>{@code onStart} itself fires once, automatically, before any test method runs -- exactly the
   * shape {@code CiRunService.onStart}/{@code sweepInterrupted} already carries, and the same fix:
   * the reconciliation is {@link #reconcileFromLog}, package-private, so a test that wants to claim
   * something about discovery seeds the log first and calls it directly.
   *
   * <p><b>Must return without probing.</b> {@link #reconcileFromLog} only adopts and enqueues -- see
   * its own javadoc for why a synchronous probe here was a boot deadlock, confirmed live.
   */
  void onStart(@Observes StartupEvent event) {
    if (!autoadoptEnabled || releaseLog.isUnsatisfied()) {
      return;
    }
    reconcileFromLog();
  }

  /**
   * The reconciliation on its own -- see {@link #onStart}'s javadoc for why it is a separate,
   * directly callable method rather than inline in the observer.
   *
   * <p><b>Adopts synchronously (a fast upsert); never probes synchronously.</b> This used to end
   * with a direct call to {@code pins.answer()} -- the ladder's probe -- reasoned as "paid up front
   * instead of on the first run". That reasoning was wrong: {@code answer()} probes any {@code
   * UNPROVEN} rung by launching a real container, and the probe dials back to this very process over
   * a socket the startup thread has not bound yet. It cannot succeed before boot finishes, so it
   * blocks for {@code qits.ci.daemon-register-timeout-seconds} (60s); the container healthcheck's
   * shorter budget (~19s) fails first, and cd kills the deployment before the socket ever opens.
   * Measured live: qits-ci {@code 0e09ca32} (2026.803.171135) failed exactly this way.
   *
   * <p>The fix hands the probe to {@code ci-daemon-adopt-worker} -- the same queue {@link #onFrame}
   * already uses for a live release -- instead of running it here. {@link #onStart} then returns and
   * the socket binds with the ladder unprobed, which is not a failure state: {@link CiDaemonPins}'s
   * own javadoc calls probing "lazy", so the first probe simply happens once, whether that is this
   * queued call or the next run's {@code pinDaemon()}. Readiness stays UP too while unprobed --
   * {@code eu.wohlben.qits.ci.api.CiDaemonReadinessCheck} is DOWN only when the ladder has fallen all
   * the way through (source {@code NONE}), and the configured pin is always available as a bottom
   * rung underneath an unprobed one.
   */
  void reconcileFromLog() {
    List<DaemonReleaseLog.Release> releases = releaseLog.get().recentReleases(2);
    for (int i = releases.size() - 1; i >= 0; i--) {
      DaemonReleaseLog.Release release = releases.get(i);
      pins.adopt(release.version(), release.eventId(), release.occurredAt());
    }
    if (!releases.isEmpty()) {
      try {
        adopter.execute(pins::answer);
      } catch (RejectedExecutionException full) {
        LOG.warn("Daemon adoption queue is full -- startup reconciliation's probe was not queued");
      }
    }
  }

  /** Test hook: waits for the adoption queued at this moment to drain -- {@code
   *  CiEventTriggerService.awaitIdle}'s own shape, for the same reason. */
  void awaitIdle() throws Exception {
    adopter.submit(() -> {}).get();
  }
}
