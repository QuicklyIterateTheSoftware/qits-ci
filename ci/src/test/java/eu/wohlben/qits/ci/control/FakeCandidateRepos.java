package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The candidate-repo seam for the ci suite: whatever the test says, and nothing from the database or
 * the filesystem. Replacing {@link KnownCiRepos} is one {@code @Mock} bean precisely because {@link
 * CiCandidateRepos} is one method — which is the swappability the shipped fallback was designed to
 * have, exercised here rather than asserted in a comment.
 *
 * <p>It also carries the one latch this suite needs: {@link #blockUntilReleased()} parks the
 * evaluation thread <em>inside</em> {@link #candidates()}, which is how "onEvent hands off rather
 * than evaluating on the caller's thread" is staged with no sleep and no race about when "still
 * evaluating" is. Same idea as {@code FakeCiStepRunner}'s {@code during} hook, one seam over.
 */
@Mock
@ApplicationScoped
public class FakeCandidateRepos implements CiCandidateRepos {

  // Read through methods: a field read on an injected CDI client proxy sees the proxy's fields.
  private final Set<String> repoIds = new LinkedHashSet<>();
  private volatile CountDownLatch gate;
  private final AtomicBoolean released = new AtomicBoolean(true);

  public void set(String... ids) {
    repoIds.clear();
    repoIds.addAll(List.of(ids));
  }

  /** The next {@link #candidates()} parks until {@link #release()}. */
  public void blockUntilReleased() {
    gate = new CountDownLatch(1);
    released.set(false);
  }

  public boolean released() {
    return released.get();
  }

  public void release() {
    CountDownLatch open = gate;
    if (open != null) {
      open.countDown();
    }
  }

  public void reset() {
    repoIds.clear();
    release();
    gate = null;
    released.set(true);
  }

  @Override
  public Set<String> candidates() {
    CountDownLatch open = gate;
    if (open != null) {
      try {
        // Bounded, like every other wait in this repository: a fixture that can hang is a suite that
        // hangs.
        open.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      released.set(true);
      gate = null;
    }
    return Set.copyOf(repoIds);
  }
}
