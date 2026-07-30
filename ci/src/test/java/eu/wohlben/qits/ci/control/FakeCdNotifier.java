package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what {@link CiRunService} announces through the {@link CdNotifier} port. State is read
 * through <b>methods</b> — the injected reference is a CDI client proxy, and a field read on a
 * proxy sees the proxy's fields, not the bean's.
 */
@Mock
@ApplicationScoped
public class FakeCdNotifier implements CdNotifier {

  public record Notified(String runId, String repoId, String branch, String commitSha) {}

  private final List<Notified> notified = Collections.synchronizedList(new ArrayList<>());

  public List<Notified> notified() {
    return List.copyOf(notified);
  }

  public void reset() {
    notified.clear();
  }

  @Override
  public void onRunSucceeded(String runId, String repoId, String branch, String commitSha) {
    notified.add(new Notified(runId, repoId, branch, commitSha));
  }
}
