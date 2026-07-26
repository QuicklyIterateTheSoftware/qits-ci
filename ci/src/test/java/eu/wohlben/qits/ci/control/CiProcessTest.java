package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bounded-output and timeout behaviour of ci's process shell-out. */
public class CiProcessTest {

  @Test
  public void capturesOutputAndExitCode() {
    CiProcess.Result result =
        CiProcess.run(
            null, List.of("bash", "-c", "echo hello; exit 3"), Duration.ofSeconds(30), 1024);
    assertEquals(3, result.exitCode());
    assertTrue(result.output().contains("hello"));
    assertFalse(result.truncated());
    assertFalse(result.timedOut());
  }

  @Test
  public void outputIsBoundedWhileReadingAndKeepsTheTail() {
    // A step's output is attacker-controlled and unbounded; the buffer must stay O(maxChars) rather
    // than materializing the whole stream on the shared qits heap.
    int max = 2048;
    CiProcess.Result result =
        CiProcess.run(
            null,
            List.of(
                "bash",
                "-c",
                "for i in $(seq 1 20000); do echo padding-line-$i; done; echo THE-END"),
            Duration.ofSeconds(60),
            max);
    assertEquals(0, result.exitCode());
    assertTrue(result.truncated(), "large output must report truncation");
    assertTrue(
        result.output().length() <= max, "buffer kept " + result.output().length() + " chars");
    assertTrue(result.output().contains("THE-END"), "the tail is what matters for a failure");
  }

  @Test
  public void timeoutKillsTheProcessAndReportsIt() {
    CiProcess.Result result =
        CiProcess.run(null, List.of("bash", "-c", "sleep 30"), Duration.ofMillis(300), 1024);
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }
}
