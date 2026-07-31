package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records what {@link CiRunService} announces through the {@link RunAnnouncer} port. Same shape and
 * same proxy caveat as {@link FakeCdNotifier}: state is read through <b>methods</b>, because a field
 * read on an injected CDI client proxy sees the proxy's fields rather than the bean's.
 */
@Mock
@ApplicationScoped
public class FakeRunAnnouncer implements RunAnnouncer {

  public record Announced(
      String runId,
      String repoId,
      String branch,
      String commitSha,
      Instant finishedAt,
      String triggerEventId) {}

  private final List<Announced> announced = Collections.synchronizedList(new ArrayList<>());

  public List<Announced> announced() {
    return List.copyOf(announced);
  }

  public void reset() {
    announced.clear();
  }

  @Override
  public void onRunSucceeded(
      String runId,
      String repoId,
      String branch,
      String commitSha,
      Instant finishedAt,
      String triggerEventId) {
    announced.add(new Announced(runId, repoId, branch, commitSha, finishedAt, triggerEventId));
  }
}
