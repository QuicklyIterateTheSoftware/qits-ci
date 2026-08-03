package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiArtifact;
import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.DaemonReleaseLog;
import eu.wohlben.qits.ci.events.SoftwareRelease;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
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
 * <h2>Raw, not typed -- a deviation from the plan's own §2.8, measured rather than assumed</h2>
 *
 * <p>§2.8 calls this a {@code QitsEventListener<SoftwareRelease>}. It cannot be one and still work:
 * {@code CanonicalJson}'s mix-in hides every method {@link eu.wohlben.qits.eventstream.QitsEvent}
 * declares, by <b>signature</b>, from every implementation uniformly -- and unlike {@code
 * BuildSuccessful} (whose payload timestamp is the differently-named {@code finishedAt}, with
 * {@code occurredAt()} overridden to forward to it), {@link SoftwareRelease#occurredAt()} <b>is</b>
 * the interface method, so it is excluded from the payload exactly like {@code eventId}. A typed
 * listener's {@code onEvent} therefore receives a {@code SoftwareRelease} rebuilt from the payload
 * alone: {@code eventId} is fresh and unrelated to the id qits-events actually stored (the same
 * "reading a payload back yields a fresh id" {@code BuildSuccessfulListener}'s own javadoc names),
 * and {@code occurredAt} is bluntly {@code null} -- measured by running exactly that path in this
 * suite before writing this class, where it threw {@code CiDaemonPins.adopt}'s own {@code
 * IllegalArgumentException("occurredAt is required")}. Both are exactly what {@link
 * CiDaemonPins#adopt} needs: the real event id, for the idempotency key a redelivery relies on, and
 * the real {@code occurredAt}, for the freshness ordering that keeps a late-delivered older release
 * from being treated as a demotion (§2.6). Neither is recoverable from the typed object.
 *
 * <p>{@link EventFrame} carries both, verbatim, because it <b>is</b> the envelope -- so this is a
 * {@link QitsRawEventListener} instead, the seam {@code CiEventTriggerListener} already uses for the
 * same reason with a wider filter. Unlike that listener, this one's interest <em>is</em> knowable at
 * startup -- one event name -- so {@link #signatures()} names it rather than answering {@code
 * Set.of(ALL)}: this consumer costs qits-events nothing beyond the one signature it actually wants.
 *
 * <h2>Why this owns a second dedicated thread, not the trigger engine's</h2>
 *
 * <p>{@link #onFrame} runs on the bus's websocket worker, one frame at a time for every consumer.
 * Adoption itself is a fast upsert, but the probe {@link CiDaemonPins#answer()} may run behind it --
 * ci-daemon-autoadopt-plan.md §2.4 calls for exactly that, asynchronously, the moment a candidate
 * lands, so {@code pinDaemon()} never has to pay for the first run's probe. A container probe is ten
 * to thirty seconds, mostly an image pull the first time, which would stall every other consumer on
 * the shared dispatch thread. Not {@code ci-trigger-worker} either -- that thread's job is
 * evaluating trigger files against candidate repositories, an unrelated latency class, and sharing
 * it would let a slow probe delay an event-triggered build's own evaluation. A third
 * single-threaded queue, sized like the trigger engine's: bounded, so a burst costs a WARN naming
 * the event rather than heap.
 */
@ApplicationScoped
public class DaemonReleaseListener implements QitsRawEventListener {

  private static final Logger LOG = Logger.getLogger(DaemonReleaseListener.class);

  /** No reflection needed -- a plain read of already-parsed platform data, the trigger engine's own
   *  precedent ({@code CiEventSelectionEvaluator}). */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Deep enough that reaching it means something is wrong rather than something is busy. */
  static final int QUEUE_CAPACITY = 256;

  @Inject CiDaemonPins pins;

  /** Zero implementations is supported (the {@code CdNotifier} precedent) -- startup discovery
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
  public Set<String> signatures() {
    return Set.of(SoftwareRelease.class.getSimpleName());
  }

  /**
   * Filters to the one daemon this ladder adopts releases for (§1.1's "out of scope") and enqueues.
   * <b>Returns immediately and never throws</b> -- the caller is a socket callback delivering to
   * other consumers too.
   */
  @Override
  public void onFrame(EventFrame frame) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(frame.payload());
    } catch (Exception e) {
      LOG.warnf("SoftwareRelease %s carried an unreadable payload: %s", frame.id(), e.toString());
      return;
    }
    String packageType = payload.path("packageType").asText(null);
    String packageName = payload.path("packageName").asText(null);
    String version = payload.path("version").asText(null);
    if (!CiArtifact.Type.DAEMON.declared().equals(packageType)
        || !CiDaemonPins.DAEMON_NAME.equals(packageName)
        || version == null
        || version.isBlank()) {
      return;
    }
    try {
      adopter.execute(() -> adoptAndProbeQuietly(version, frame.id(), frame.occurredAt()));
    } catch (RejectedExecutionException full) {
      LOG.warnf(
          "Daemon adoption queue is full -- release %s (eventId=%s) was not adopted",
          version, frame.id());
    }
  }

  private void adoptAndProbeQuietly(String version, String eventId, Instant occurredAt) {
    try {
      pins.adopt(version, eventId, occurredAt);
      // The async probe-on-adoption ci-daemon-autoadopt-plan.md §2.4 asks for: answer() walks the
      // ladder and probes any UNPROVEN rung it passes, persisting the verdict before returning. On
      // this dedicated thread that costs nobody else anything, and it is what lets pinDaemon() find
      // an already-proven top rung on the very next run rather than paying for the first one's probe.
      pins.answer();
    } catch (RuntimeException e) {
      LOG.errorf(e, "Adopting daemon release %s (eventId=%s) failed unexpectedly", version, eventId);
    }
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
