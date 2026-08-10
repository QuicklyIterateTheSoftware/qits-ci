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
 * <p>It also carries the two latches this suite needs, and they park different threads on purpose:
 *
 * <ul>
 *   <li>{@link #blockUntilReleased()} parks <b>whichever thread arrives first</b> inside {@link
 *       #candidates()}, which is how "onEvent hands off rather than evaluating on the caller's
 *       thread" is staged with no sleep and no race about when "still evaluating" is.
 *   <li>{@link #wedgeTheTriggerWorker()} parks <b>only {@code ci-trigger-worker}</b>, and stays
 *       armed. That is the incident of 2026-08-10 in a fixture: the one evaluation thread stuck,
 *       everything behind it waiting in the bounded queue, and nothing logged about either. A
 *       caller-supplied event must survive it, so the manual path has to run beside it — which a
 *       latch keyed on the thread name is exactly able to say.
 * </ul>
 *
 * <p>Same idea as {@code FakeCiStepRunner}'s {@code during} hook, one seam over.
 */
@Mock
@ApplicationScoped
public class FakeCandidateRepos implements CiCandidateRepos {

  /** The engine's evaluation thread, by the name {@code CiEventTriggerService} gives it. */
  private static final String TRIGGER_WORKER = "ci-trigger-worker";

  // Read through methods: a field read on an injected CDI client proxy sees the proxy's fields.
  private final Set<String> repoIds = new LinkedHashSet<>();
  private volatile CountDownLatch gate;
  private final AtomicBoolean released = new AtomicBoolean(true);
  private volatile CountDownLatch workerGate;
  private volatile CountDownLatch workerWedged;

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

  /** Every evaluation on {@code ci-trigger-worker} parks here until {@link #freeTheTriggerWorker()}. */
  public void wedgeTheTriggerWorker() {
    workerWedged = new CountDownLatch(1);
    workerGate = new CountDownLatch(1);
  }

  /** Blocks until the trigger worker really is parked, so a test never races its own fixture. */
  public void awaitTriggerWorkerWedged() throws InterruptedException {
    CountDownLatch stuck = workerWedged;
    if (stuck != null && !stuck.await(30, TimeUnit.SECONDS)) {
      throw new IllegalStateException("the trigger worker never reached the wedge");
    }
  }

  public void freeTheTriggerWorker() {
    CountDownLatch open = workerGate;
    workerGate = null;
    if (open != null) {
      open.countDown();
    }
  }

  public void reset() {
    repoIds.clear();
    release();
    freeTheTriggerWorker();
    gate = null;
    workerWedged = null;
    released.set(true);
  }

  @Override
  public Set<String> candidates() {
    CountDownLatch worker = workerGate;
    if (worker != null && TRIGGER_WORKER.equals(Thread.currentThread().getName())) {
      workerWedged.countDown();
      try {
        // Bounded, for the reason below: a fixture that can hang is a suite that hangs.
        worker.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
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
