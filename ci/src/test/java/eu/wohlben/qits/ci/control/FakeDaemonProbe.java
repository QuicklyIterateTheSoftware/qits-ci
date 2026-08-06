package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.control.DaemonProbe.ProbeResult;
import eu.wohlben.qits.ci.control.DaemonProbe.Verdict;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * A scripted {@link DaemonProbe}: each test tells it what verdict to answer for a version before
 * {@link CiDaemonPins} can ask. State is read/written through methods on the injected reference,
 * the same convention {@link FakePdNotifier} uses.
 *
 * <p><b>{@link #blockOn} adds a controllable pause inside {@link #probe}</b>, for
 * {@code CiDaemonPinsTest}'s single-flight test: it needs two threads to both reach {@code
 * CiDaemonPins}'s probing path for the same still-unproven candidate, with the first one held inside
 * the probe call until the test has confirmed the second thread was blocked by the single-flight
 * guard rather than by having not yet arrived.
 */
@Mock
@ApplicationScoped
public class FakeDaemonProbe implements DaemonProbe {

  private final Map<String, ProbeResult> scripted = new ConcurrentHashMap<>();
  private final List<String> probed = Collections.synchronizedList(new ArrayList<>());

  private volatile String blockingVersion;
  private volatile CountDownLatch entered = new CountDownLatch(1);
  private volatile CountDownLatch released = new CountDownLatch(1);

  public void willAnswer(String version, Verdict verdict, String detail) {
    scripted.put(version, new ProbeResult(verdict, detail));
  }

  /** Every version this fake was actually asked to probe, in call order. */
  public List<String> probed() {
    return List.copyOf(probed);
  }

  public void reset() {
    scripted.clear();
    probed.clear();
    blockingVersion = null;
    entered = new CountDownLatch(1);
    released = new CountDownLatch(1);
  }

  /** From the next call to {@link #probe} for {@code version} onward, block inside the call until
   *  {@link #release} is called. */
  public void blockOn(String version) {
    blockingVersion = version;
  }

  /** Waits until a thread is actually parked inside {@link #probe} for the blocked version. */
  public void awaitEntered() throws InterruptedException {
    entered.await();
  }

  /** Releases whatever call is parked inside {@link #probe}. */
  public void release() {
    released.countDown();
  }

  @Override
  public ProbeResult probe(String version) {
    probed.add(version);
    if (version.equals(blockingVersion)) {
      entered.countDown();
      try {
        released.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    ProbeResult result = scripted.get(version);
    if (result == null) {
      throw new IllegalStateException("FakeDaemonProbe was not told what to answer for " + version);
    }
    return result;
  }
}
