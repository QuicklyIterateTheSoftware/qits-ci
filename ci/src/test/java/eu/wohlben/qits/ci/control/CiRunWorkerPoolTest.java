package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CiRunWorkerPoolTest {

  @Test
  void configuredBuildSlotsCanExecuteAtTheSameTime() throws Exception {
    ExecutorService workers = CiRunService.createWorkerPool(2);
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try {
      for (int i = 0; i < 2; i++) {
        workers.submit(
            () -> {
              bothStarted.countDown();
              release.await();
              return null;
            });
      }

      assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "both configured build slots started");
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  @Test
  void atLeastOneBuildSlotIsRequired() {
    assertThrows(IllegalArgumentException.class, () -> CiRunService.createWorkerPool(0));
  }
}
