package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiDaemonPin;
import eu.wohlben.qits.ci.entity.CiDaemonPinVerdict;
import eu.wohlben.qits.ci.error.BadRequestException;
import eu.wohlben.qits.ci.persistence.CiDaemonPinRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The daemon pin ladder (ci-daemon-autoadopt-plan.md §2.1): an ordered, durable list of candidate
 * daemon versions, each with a verdict, plus the configured pin as the one rung that is never a row
 * and never demoted. {@link #answer} is the whole read side -- the top {@code PROVEN} rung, or the
 * configured pin when nothing adopted has proven itself yet, or blank when neither exists.
 *
 * <p><b>The two rungs, highest first.</b> Adopted candidates (this table, newest event first), then
 * {@code qits.ci.daemon-version} (never a row, never demoted). An adopted candidate can be probed
 * down to {@code REJECTED}; the configured pin cannot be probed at all -- it is read straight from
 * config, exactly as it always was, which is what makes a qits-ci rollback safe (a plan a rollback
 * deployment set is not something this class can take away).
 *
 * <p><b>Probing is lazy here and asynchronous elsewhere.</b> This class only ever probes a candidate
 * when {@link #answer} walks past it still unproven -- the rung that makes the ladder correct with
 * no async probe having run yet, since {@code CiStepRunner.pinDaemon()} calls through to this before
 * a run's first container. The <em>async</em> probe-on-adoption in ci-daemon-autoadopt-plan.md §2.4
 * is a caller's job (the bus listener, once it exists), not this class's -- {@link #adopt} only ever
 * stores a candidate {@code UNPROVEN}.
 *
 * <p><b>{@code UNKNOWN} is retryable; {@code REJECTED} is not.</b> {@code UNKNOWN} means the probe
 * itself could not run -- no docker, a container-name collision, an unpullable image -- which is a
 * statement about the probe, not the candidate, so {@link #answer} reprobes it on a later call
 * exactly like a still-{@code UNPROVEN} rung. {@code REJECTED} means a real daemon dialled in and was
 * found wanting on its own merits (wrong capability version, no confirmed {@code Ack}) -- a statement
 * about the candidate, and it stays durable and un-retried.
 *
 * <p><b>At most one probe of a given candidate runs at a time, guarded per version.</b> A single
 * global lock would also satisfy that, but two concurrent callers probing two genuinely different
 * still-unproven candidates should not have to wait on each other, and a per-version {@link Set}
 * costs nothing extra to keep instead. A caller that loses the race neither blocks nor launches a
 * second probe -- it answers with the candidate's current (unchanged) verdict, which is not {@code
 * PROVEN}, so {@link #firstProvenIndex} falls through to whatever is below it exactly as it would
 * for a rung it had not reached yet. This is the fix for the concrete incident that motivated it: two
 * concurrent callers each walking the ladder to the same still-unproven top candidate used to both
 * launch a probe container, and the two containers raced docker's own container-name uniqueness.
 *
 * <p><b>Zero {@link DaemonProbe} implementations is a supported configuration</b>, the {@link
 * RunAnnouncer} precedent: with none wired, every probe answers {@code UNKNOWN} and the ladder never
 * rises above the configured pin -- correct, since nothing exists yet that could have proven a
 * candidate.
 */
@ApplicationScoped
public class CiDaemonPins {

  private static final Logger LOG = Logger.getLogger(CiDaemonPins.class);

  /** The one daemon this ladder adopts releases for -- see the plan's "out of scope" list. */
  public static final String DAEMON_NAME = "qits-ci-daemon";

  public static final String SOURCE_ADOPTED = "adopted";
  public static final String SOURCE_CONFIGURED = "configured";
  public static final String SOURCE_NONE = "none";

  @Inject CiDaemonPinRepository repo;

  @Inject Instance<DaemonProbe> probes;

  /**
   * The single-flight guard: candidate versions this instance is probing right now. {@code add} is
   * the acquire (it fails, returning {@code false}, when the version is already present); {@code
   * remove} in a {@code finally} is the release. See the class javadoc for why this is per-version
   * rather than one lock for the whole ladder.
   */
  private final Set<String> probing = ConcurrentHashMap.newKeySet();

  /**
   * The deployment's own pin -- see {@code microprofile-config.properties}. Blank is valid and
   * means this deployment has pinned no daemon yet.
   *
   * <p>Public, unlike this class's other injected fields, because {@code CiDaemonPinTest} (in
   * {@code service}, a different package) stages it through {@link io.quarkus.arc.ClientProxy}
   * exactly as it once staged {@code CiDaemonLauncher.daemonVersion} when the two lived in the same
   * package -- see that test's own javadoc.
   */
  @ConfigProperty(name = "qits.ci.daemon-version")
  public Optional<String> configuredVersion;

  /** What a run started right now would download, and what would fall back if it did not work --
   *  the shape {@code GET /ci/api/daemon} answers verbatim (plan §2.7). */
  public record Pin(String version, String previousVersion, String source) {}

  /**
   * The top {@code PROVEN} rung. Probes any {@code UNPROVEN} or {@code UNKNOWN} candidate it has to
   * walk past to find one, persisting the verdict before returning -- so a run that calls this next
   * never records a pin that was only provisionally proven.
   *
   * <p>May launch a probe container. A caller that must never do that -- a health check, a public
   * read endpoint -- calls {@link #currentAnswer} instead; read its javadoc alongside this one.
   */
  public Pin answer() {
    return walk(true);
  }

  /**
   * {@link #answer}'s read-only twin. Walks the exact same ladder -- including {@code
   * previousVersion} -- but never probes: an {@code UNPROVEN} or {@code UNKNOWN} candidate it walks
   * past is simply not proven yet for this read, and the walk continues to what is below it (an
   * older adopted candidate, or the configured pin) exactly as if a concurrent single-flight guard
   * had blocked it from probing.
   *
   * <p>Exists because two read-only surfaces reach {@code answer()}'s ladder far more often than any
   * run does: {@code CiDaemonReadinessCheck}, hit by the container healthcheck every few seconds, and
   * {@code CiDaemonController}'s {@code GET /ci/api/daemon}, a public unguarded endpoint. Either one
   * calling {@code answer()} means a probe container can be launched on that cadence -- the amplifier
   * that turned one docker container-naming race into a near-certain collision. Neither surface needs
   * a fresh verdict, only today's best-known one, with no side effect.
   */
  public Pin currentAnswer() {
    return walk(false);
  }

  private Pin walk(boolean allowProbing) {
    List<CiDaemonPin> ordered = QuarkusTransaction.requiringNew().call(repo::listNewestFirst);
    int topIndex = firstProvenIndex(ordered, 0, allowProbing);
    if (topIndex >= 0) {
      CiDaemonPin top = ordered.get(topIndex);
      int nextIndex = firstProvenIndex(ordered, topIndex + 1, allowProbing);
      String previous = nextIndex >= 0 ? ordered.get(nextIndex).version : "";
      return new Pin(top.version, previous, SOURCE_ADOPTED);
    }
    String configured = configuredVersion.map(String::trim).orElse("");
    if (configured.isEmpty()) {
      return new Pin("", "", SOURCE_NONE);
    }
    return new Pin(configured, "", SOURCE_CONFIGURED);
  }

  /**
   * Every candidate this instance has rejected, newest first -- what the readiness health check
   * (workstream BX) reports as data when the ladder has fallen all the way through to blank (§2.5,
   * ⚖6(a)). Empty on a healthy ladder; a version can appear here only once, since a verdict is
   * durable and a rejected candidate is never re-probed.
   */
  public List<String> rejectedVersions() {
    return QuarkusTransaction.requiringNew()
        .call(() -> repo.listRejected().stream().map(pin -> pin.version).toList());
  }

  /**
   * Adopts one release off the event log -- a live {@code SoftwareRelease} or a startup
   * reconciliation row, the two callers this method does not need to tell apart. Stores the
   * candidate {@code UNPROVEN}; nothing here probes it.
   *
   * <p>Refuses (with a WARN, never an exception -- this runs off a bus frame and off a startup read,
   * neither of which may fail loudly for one bad release) when: this event id already adopted a
   * candidate (idempotent replay); the version fails the single-segment shape check
   * ({@link CiIdentifiers#requireDaemonVersion}); the event is not newer than the newest
   * already-adopted candidate's {@code occurredAt} (a late-delivered older release, ignored rather
   * than treated as a demotion -- calver is never parsed or compared, see plan §2.6); or the version
   * is already adopted under a different event id (defensive -- the unique constraint would refuse
   * the insert regardless).
   */
  public void adopt(String version, String eventId, Instant occurredAt) {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("eventId is required to adopt a daemon release");
    }
    if (occurredAt == null) {
      throw new IllegalArgumentException("occurredAt is required to adopt a daemon release");
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              if (repo.findByEventId(eventId).isPresent()) {
                return;
              }
              String shapeChecked;
              try {
                shapeChecked = CiIdentifiers.requireDaemonVersion(version);
              } catch (BadRequestException e) {
                LOG.warnf(
                    "Refusing to adopt daemon release '%s' (eventId=%s): %s",
                    version, eventId, e.getMessage());
                return;
              }
              Optional<CiDaemonPin> newest = repo.newestAdopted();
              if (newest.isPresent() && !occurredAt.isAfter(newest.get().occurredAt)) {
                LOG.infof(
                    "Ignoring daemon release %s (eventId=%s): not newer than the newest adopted"
                        + " candidate %s",
                    shapeChecked, eventId, newest.get().version);
                return;
              }
              if (repo.findByVersion(shapeChecked).isPresent()) {
                LOG.warnf(
                    "Daemon release %s is already adopted under a different event id; ignoring"
                        + " eventId=%s",
                    shapeChecked, eventId);
                return;
              }
              CiDaemonPin row = new CiDaemonPin();
              row.id = UUID.randomUUID().toString();
              row.version = shapeChecked;
              row.source = SOURCE_ADOPTED;
              row.verdict = CiDaemonPinVerdict.UNPROVEN;
              row.eventId = eventId;
              row.occurredAt = occurredAt;
              repo.persist(row);
            });
  }

  /**
   * The index of the first candidate at or after {@code fromIndex} that is (or becomes, once
   * probed) {@code PROVEN}; {@code -1} when none is. {@code REJECTED} stays skipped forever -- it is
   * a durable verdict about the candidate itself. {@code UNPROVEN} and {@code UNKNOWN} are both
   * eligible for a probe when {@code allowProbing} is set (see the class javadoc on why {@code
   * UNKNOWN} is retryable); with it unset ({@link #currentAnswer}) neither is ever touched and the
   * walk simply falls through them, unresolved for this read.
   */
  private int firstProvenIndex(List<CiDaemonPin> ordered, int fromIndex, boolean allowProbing) {
    for (int i = fromIndex; i < ordered.size(); i++) {
      CiDaemonPin candidate = ordered.get(i);
      CiDaemonPinVerdict verdict = candidate.verdict;
      if (allowProbing && isReprobable(verdict)) {
        verdict = probeSingleFlight(candidate);
      }
      if (verdict == CiDaemonPinVerdict.PROVEN) {
        return i;
      }
    }
    return -1;
  }

  /** {@code UNPROVEN} or {@code UNKNOWN} -- the two verdicts {@link #firstProvenIndex} may reprobe. */
  private static boolean isReprobable(CiDaemonPinVerdict verdict) {
    return verdict == CiDaemonPinVerdict.UNPROVEN || verdict == CiDaemonPinVerdict.UNKNOWN;
  }

  /**
   * {@link #probeAndRecord}, single-flight per candidate version -- see the class javadoc. A caller
   * that fails to acquire {@link #probing} does not wait and does not probe; it returns the
   * candidate's current (unchanged) verdict, which {@link #firstProvenIndex} then treats exactly like
   * a rung it has not reached yet.
   */
  private CiDaemonPinVerdict probeSingleFlight(CiDaemonPin candidate) {
    if (!probing.add(candidate.version)) {
      return candidate.verdict;
    }
    try {
      return probeAndRecord(candidate);
    } finally {
      probing.remove(candidate.version);
    }
  }

  /**
   * Runs the probe for one candidate and persists the verdict in its own transaction -- outside any
   * transaction {@link #answer} might otherwise have held open across a probe that can take tens of
   * seconds. The write guard accepts a row currently {@code UNPROVEN} <em>or</em> {@code UNKNOWN} --
   * the same two verdicts {@link #isReprobable} names -- so a retried {@code UNKNOWN} candidate's
   * fresh verdict is actually persisted instead of silently dropped because the row no longer reads
   * {@code UNPROVEN}. {@link #probeSingleFlight} is what keeps two threads from racing this method
   * for the same candidate at all; this guard is defensive on top of that, not a promised lock.
   */
  private CiDaemonPinVerdict probeAndRecord(CiDaemonPin candidate) {
    DaemonProbe.ProbeResult result = runProbe(candidate.version);
    CiDaemonPinVerdict verdict = toEntityVerdict(result.verdict());
    Instant probedAt = Instant.now();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CiDaemonPin row = repo.findById(candidate.id);
              if (row != null && isReprobable(row.verdict)) {
                row.verdict = verdict;
                row.probedAt = probedAt;
                row.detail = result.detail();
              }
            });
    if (result.verdict() != DaemonProbe.Verdict.PROVEN) {
      LOG.warnf(
          "Daemon candidate %s probed %s: %s", candidate.version, result.verdict(), result.detail());
    }
    return verdict;
  }

  /** No {@link DaemonProbe} wired is not a bug -- see the class javadoc -- so it answers exactly
   *  what an environmental probe failure would: {@code UNKNOWN}, never {@code PROVEN}. */
  private DaemonProbe.ProbeResult runProbe(String version) {
    if (probes.isUnsatisfied()) {
      return new DaemonProbe.ProbeResult(
          DaemonProbe.Verdict.UNKNOWN, "no daemon probe implementation is wired");
    }
    try {
      return probes.get().probe(version);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Daemon probe for %s threw; treating as UNKNOWN", version);
      return new DaemonProbe.ProbeResult(DaemonProbe.Verdict.UNKNOWN, e.getMessage());
    }
  }

  private static CiDaemonPinVerdict toEntityVerdict(DaemonProbe.Verdict verdict) {
    return switch (verdict) {
      case PROVEN -> CiDaemonPinVerdict.PROVEN;
      case REJECTED -> CiDaemonPinVerdict.REJECTED;
      case UNKNOWN -> CiDaemonPinVerdict.UNKNOWN;
    };
  }
}
