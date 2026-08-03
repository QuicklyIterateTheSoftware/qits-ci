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
 * A scripted {@link DaemonProbe}: each test tells it what verdict to answer for a version before
 * {@link CiDaemonPins} can ask. State is read/written through methods on the injected reference,
 * the same convention {@link FakeCdNotifier} uses.
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
