package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces {@link CiDockerRunner} for the ci suite (the {@code FakeContainerRuntime} seam pattern):
 * no docker — tests script a {@link StepResult} per step index and inspect what was executed. The
 * default for an unscripted step is a green result with a ready workspace.
 */
@Mock
@ApplicationScoped
public class FakeCiStepRunner implements CiStepRunner {

  // Accessed via executed() — a direct field read through the CDI client proxy would see the
  // proxy's own (empty) field, not the contextual instance's.
  private final List<StepSpec> executed = new ArrayList<>();
  private final Map<Integer, StepResult> scripted = new HashMap<>();
  private final Map<Integer, RuntimeException> failures = new HashMap<>();

  public List<StepSpec> executed() {
    return executed;
  }

  public void script(int stepIndex, StepResult result) {
    scripted.put(stepIndex, result);
  }

  /**
   * Makes the step blow up instead of returning — stands in for a transient infrastructure error.
   */
  public void throwOn(int stepIndex, RuntimeException failure) {
    failures.put(stepIndex, failure);
  }

  public void reset() {
    executed.clear();
    scripted.clear();
    failures.clear();
  }

  @Override
  public StepResult run(StepSpec spec) {
    executed.add(spec);
    RuntimeException failure = failures.get(spec.stepIndex());
    if (failure != null) {
      throw failure;
    }
    return scripted.getOrDefault(
        spec.stepIndex(), new StepResult(0, "ok step " + spec.stepIndex(), false, true));
  }
}
