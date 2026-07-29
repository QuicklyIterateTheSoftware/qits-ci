package eu.wohlben.qits.ci.daemonhost;

import eu.wohlben.qits.ci.control.CiRunService;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What the step running <em>right now</em> has printed, per run. One bounded buffer per in-flight
 * run, fed by the chunks arriving on that run's container's control socket, read by the run read
 * surface, and dropped when the run closes.
 *
 * <p><b>It is the only thing that exists while a step runs.</b> Step rows are written once and
 * already terminal, so between two steps a run legitimately has fewer rows than its pipeline
 * declares; this is what makes that gap legible instead of looking like a run with missing steps.
 * The persisted tail on the step row is the record — this is the live convenience, it is memory, and
 * it dies with the process. Following along is a <b>poll</b> over {@code GET /ci/api/runs/{runId}}:
 * the daemon makes live output possible, it does not oblige a push transport, and there is no SSE
 * and no WebSocket on the read side.
 *
 * <p><b>Bounded by construction.</b> A step's output is repo-controlled and unbounded — a chatty
 * build, an accidental {@code cat} of a huge file — so the budget ({@code qits.ci.output-max-chars})
 * is applied as text arrives rather than to an assembled string, and the head is what goes. Which
 * makes this buffer the runner's accumulator too: the tail the step row is written with is read back
 * out of here, so the bound is applied once, in one place, rather than twice with a chance to drift.
 *
 * <p>Chunks arrive on the socket's virtual thread and the read arrives on an HTTP worker, so every
 * buffer method is synchronized on the buffer. Nothing here blocks for longer than an append.
 */
@ApplicationScoped
public class CiStepRelay {

  /** How much of a truncated tail is text, once the marker has taken its share. */
  private static final int MARKER_LENGTH = CiRunService.TRUNCATION_MARKER.length();

  @ConfigProperty(name = "qits.ci.output-max-chars")
  int outputMaxChars;

  private final ConcurrentHashMap<String, Buffer> live = new ConcurrentHashMap<>();

  /** Which step a run is on and what it has printed so far. */
  public record Snapshot(int stepIndex, String output) {}

  /** Start relaying a step. Replaces whatever the run's previous step left behind. */
  public void begin(String runId, int stepIndex) {
    live.put(runId, new Buffer(stepIndex, Math.max(MARKER_LENGTH + 1, outputMaxChars)));
  }

  /** Append one chunk. A chunk for a run with no live step is dropped, not resurrected. */
  public void append(String runId, String text) {
    Buffer buffer = live.get(runId);
    if (buffer != null && text != null) {
      buffer.append(text);
    }
  }

  /** The bounded tail of the run's current step, marked if its head was dropped. */
  public Optional<Snapshot> snapshot(String runId) {
    Buffer buffer = live.get(runId);
    return buffer == null
        ? Optional.empty()
        : Optional.of(new Snapshot(buffer.stepIndex, buffer.text()));
  }

  /** Forget a run's live output. Called when its run closes, however it closed. */
  public void drop(String runId) {
    live.remove(runId);
  }

  /** Observational: how many runs are relaying. Zero between runs. */
  public int size() {
    return live.size();
  }

  /**
   * A rolling tail. Trimming happens at twice the budget so a chatty step costs one array copy per
   * {@code maxChars} of output rather than one per chunk, and {@link #text()} trims exactly.
   */
  private static final class Buffer {

    private static final int TRIM_FACTOR = 2;

    private final int stepIndex;
    private final int maxChars;
    private final StringBuilder text = new StringBuilder();
    private boolean truncated;

    Buffer(int stepIndex, int maxChars) {
      this.stepIndex = stepIndex;
      this.maxChars = maxChars;
    }

    synchronized void append(String chunk) {
      text.append(chunk);
      if (text.length() > (long) maxChars * TRIM_FACTOR) {
        text.delete(0, text.length() - maxChars);
        truncated = true;
      }
    }

    /**
     * The tail, never longer than the budget <em>including</em> the marker — so the caller can write
     * it straight onto a step row without a second truncation pass that would eat the marker it just
     * read.
     */
    synchronized String text() {
      if (text.length() > maxChars) {
        text.delete(0, text.length() - maxChars);
        truncated = true;
      }
      if (!truncated) {
        return text.toString();
      }
      int keep = Math.min(text.length(), maxChars - MARKER_LENGTH);
      return CiRunService.TRUNCATION_MARKER + text.substring(text.length() - keep);
    }
  }
}
