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

/**
 * A scripted {@link DaemonProbe} for the {@code service} module's own suite -- a second copy of
 * {@code ci}'s {@code FakeDaemonProbe}, duplicated for the reason {@code FakeCiStepRunner} is: the
 * two modules do not share a test classpath. Without this, {@link CiDaemonPins#answer()} in this
 * module's tests would resolve {@code Instance<DaemonProbe>} to the real {@link
 * eu.wohlben.qits.ci.daemonhost.CiDaemonContainerProbe}, which answers {@code UNKNOWN} under {@code
 * LaunchMode.TEST} rather than a scripted verdict -- fine for proving the ladder never rises with no
 * probe wired, useless for proving what happens when one says {@code REJECTED}.
 *
 * <p>{@code CiDaemonContainerProbeTest} is unaffected: it injects the concrete {@code
 * CiDaemonContainerProbe} type directly rather than through {@code Instance<DaemonProbe>}, which
 * {@code @Mock} does not touch.
 */
@Mock
@ApplicationScoped
public class FakeDaemonProbe implements DaemonProbe {

  private final Map<String, ProbeResult> scripted = new ConcurrentHashMap<>();
  private final List<String> probed = Collections.synchronizedList(new ArrayList<>());

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
  }

  @Override
  public ProbeResult probe(String version) {
    probed.add(version);
    ProbeResult result = scripted.get(version);
    if (result == null) {
      throw new IllegalStateException("FakeDaemonProbe was not told what to answer for " + version);
    }
    return result;
  }
}
