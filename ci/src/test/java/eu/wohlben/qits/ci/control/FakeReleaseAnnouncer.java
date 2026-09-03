package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what {@link CiRunService} announces through the {@link ReleaseAnnouncer} port. Same shape
 * and same proxy caveat as {@link FakeRunAnnouncer}: state is read through <b>methods</b>, because a
 * field read on an injected CDI client proxy sees the proxy's fields rather than the bean's.
 */
@Mock
@ApplicationScoped
public class FakeReleaseAnnouncer implements ReleaseAnnouncer {

  public record Published(
      String runId,
      String repoId,
      String projectId,
      String version,
      String packageType,
      String packageName,
      Instant finishedAt,
      String triggerEventId) {}

  private final List<Published> published = Collections.synchronizedList(new ArrayList<>());

  public List<Published> published() {
    return List.copyOf(published);
  }

  public void reset() {
    published.clear();
  }

  @Override
  public void onArtifactPublished(
      String runId,
      String repoId,
      String projectId,
      String version,
      String packageType,
      String packageName,
      Instant finishedAt,
      String triggerEventId) {
    published.add(
        new Published(
            runId,
            repoId,
            projectId,
            version,
            packageType,
            packageName,
            finishedAt,
            triggerEventId));
  }
}
