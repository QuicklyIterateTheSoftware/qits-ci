package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.daemonhost.CiDaemonLauncher;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableObserverMethod;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Which half of the boot reconciliation runs first, and that saying so works.</b> {@code
 * CiDaemonLauncher.onStart} reaps the previous life's step containers and {@code
 * CiRunService.onStart} sweeps the rows those containers belonged to; both observe {@link
 * StartupEvent}, and CDI gives two observers of one event no order unless they ask for one.
 *
 * <p><b>The order is reap, then sweep.</b> The sweep does not only write rows — it hands work
 * straight back to the run worker, restarting every interrupted event run and re-enqueueing every
 * {@code QUEUED} one, and that worker asks for step containers as soon as it has work. The reap
 * asks the orchestrator for every one of this owner's own {@code ci-step} places, and a container a
 * restarted run has just asked for is <em>also</em> one of this owner's — so a reap running second
 * would be free to remove it. Reaping first closes the window: nothing can start a run until the
 * previous life's containers are gone.
 *
 * <p><b>The cutover narrowed the scope and did not remove the need for the order</b>, which is
 * worth stating because it looks as though it should have. What used to select was a host-wide
 * {@code qits.ci.run} label filter, which could reach another qits-ci's in-flight step; it is this
 * owner's rows now, and no other instance is reachable at all. What that does not fix is this
 * instance racing itself. The second net is {@code createdBefore}, stamped once at the reap
 * observer's entry, so a place created afterwards is outside the set by construction — order first,
 * instant second, and neither makes the other unnecessary.
 *
 * <p>Three claims, and the first two are the ones that would rot: the annotations are on both
 * observers and ordered the right way round, the container resolves them in that order, and — the
 * behavioural one — a pair of probe observers really is notified in priority order, so the
 * resolution above is a statement about what happens rather than about a list.
 *
 * <p>This is a plain {@code @QuarkusTest} rather than part of {@code CiRestartReconciliationIT}
 * because that class is tagged {@code extended} and needs docker: the order must be guarded by the
 * default {@code mvn verify}, which is where a refactor would break it.
 */
@QuarkusTest
public class BootReconciliationOrderTest {

  /** What each probe observer appends when it is notified, in the order they were notified. */
  private static final List<String> NOTIFIED = Collections.synchronizedList(new ArrayList<>());

  @Test
  public void bothObserversDeclareAPriorityAndTheReapIsTheLowerOne() throws Exception {
    int reap = observerPriority(CiDaemonLauncher.class);
    int sweep = observerPriority(CiRunService.class);
    assertTrue(
        reap < sweep,
        "the container reap must be prioritised ahead of the run sweep, got " + reap + " vs " + sweep);
  }

  @Test
  public void theContainerResolvesTheTwoStartupObserversInThatOrder() {
    List<InjectableObserverMethod<? super StartupEvent>> observers =
        Arc.container().resolveObserverMethods(StartupEvent.class);
    int reap = indexOfObserverOn(observers, CiDaemonLauncher.class);
    int sweep = indexOfObserverOn(observers, CiRunService.class);
    assertTrue(reap >= 0, "CiDaemonLauncher observes StartupEvent and must be resolvable");
    assertTrue(sweep >= 0, "CiRunService observes StartupEvent and must be resolvable");
    assertTrue(reap < sweep, "the reap must be notified first, got " + reap + " then " + sweep);
  }

  /**
   * The behavioural half: these two fired at this application's own startup, long before any test
   * method ran, and the list they wrote is the notification order rather than a resolution order.
   * Without it the assertion above would only say that ArC sorts a list it hands out.
   *
   * <p>Read as a repeating pair rather than as one, because a suite that restarts Quarkus for
   * another profile appends a second startup's records to the same list.
   */
  @Test
  public void arcNotifiesStartupObserversInPriorityOrder() {
    List<String> notified = List.copyOf(NOTIFIED);
    assertTrue(notified.size() >= 2, "both probe observers must have been notified: " + notified);
    assertEquals(0, notified.size() % 2, "each startup notifies both probes: " + notified);
    for (int i = 0; i < notified.size(); i += 2) {
      assertEquals(List.of("reap-side", "sweep-side"), notified.subList(i, i + 2), "" + notified);
    }
  }

  private static int observerPriority(Class<?> beanClass) throws Exception {
    Method onStart = beanClass.getDeclaredMethod("onStart", StartupEvent.class);
    for (Annotation annotation : onStart.getParameterAnnotations()[0]) {
      if (annotation instanceof Priority priority) {
        return priority.value();
      }
    }
    throw new AssertionError(beanClass.getSimpleName() + ".onStart declares no @Priority");
  }

  private static int indexOfObserverOn(
      List<InjectableObserverMethod<? super StartupEvent>> observers, Class<?> beanClass) {
    for (int i = 0; i < observers.size(); i++) {
      if (beanClass.equals(observers.get(i).getBeanClass())) {
        return i;
      }
    }
    return -1;
  }

  /** Stands where the launcher's reap stands, with its priority. */
  @ApplicationScoped
  public static class ReapSideProbe {
    void onStart(@Observes @Priority(CiDaemonLauncher.BOOT_REAP_PRIORITY) StartupEvent event) {
      NOTIFIED.add("reap-side");
    }
  }

  /** Stands where the run sweep stands, with its priority. */
  @ApplicationScoped
  public static class SweepSideProbe {
    void onStart(@Observes @Priority(CiRunService.BOOT_SWEEP_PRIORITY) StartupEvent event) {
      NOTIFIED.add("sweep-side");
    }
  }
}
