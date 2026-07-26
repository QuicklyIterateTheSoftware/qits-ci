package eu.wohlben.qits.ci.control;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ci's own tiny process shell-out (the {@code DockerExecutor.runCapturing} shape plus the {@code
 * ProcessExecutor} hard timeout) — deliberately NOT shared with {@code domain}'s executors, so the
 * runner leaves with the module. Combined stdout+stderr, drained on a virtual thread; on timeout
 * the process is {@link Process#destroyForcibly() force-killed} and whatever output was captured so
 * far is returned with {@code timedOut=true}.
 *
 * <p>Output is <b>bounded while reading</b>: the buffer keeps only the trailing {@code maxChars} (a
 * step's tail is where the failure is) and reports {@code truncated}. This is not merely cosmetic —
 * a CI step's output is attacker-controlled and unbounded (a chatty build, an accidental {@code
 * cat} of a huge file), and buffering it whole would let one step OOM the shared qits JVM.
 */
final class CiProcess {

  /** Rolling buffer slack: trim back to {@code maxChars} once it grows past this multiple. */
  private static final int TRIM_FACTOR = 2;

  /**
   * Exit code, bounded combined output, whether the hard timeout expired ({@code exitCode} is -1
   * then), and whether output was dropped from the front.
   */
  record Result(int exitCode, String output, boolean timedOut, boolean truncated) {}

  private CiProcess() {}

  static Result run(Path cwd, List<String> command, Duration timeout, int maxChars) {
    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      if (cwd != null) {
        pb.directory(cwd.toFile());
      }
      pb.redirectErrorStream(true);
      Process process = pb.start();
      Tail tail = new Tail(maxChars);
      Thread reader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = process.getInputStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                  }
                } catch (Exception ignored) {
                  // stream closes when the process dies — nothing to report beyond the exit code
                }
              });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new Result(
          finished ? process.exitValue() : -1, tail.text(), !finished, tail.truncated());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(-1, "interrupted", false, false);
    } catch (Exception e) {
      return new Result(-1, String.valueOf(e.getMessage()), false, false);
    }
  }

  /** A synchronized rolling tail — appends are trimmed so memory stays O(maxChars). */
  private static final class Tail {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;
    private boolean truncated;

    Tail(int maxChars) {
      this.maxChars = Math.max(1, maxChars);
    }

    synchronized void append(String chunk) {
      buffer.append(chunk);
      if (buffer.length() > (long) maxChars * TRIM_FACTOR) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
    }

    synchronized String text() {
      if (buffer.length() > maxChars) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
      return buffer.toString();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }
}
