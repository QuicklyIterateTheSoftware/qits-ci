package eu.wohlben.qits.ci.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The step-runner seam for the ci suite: a <b>scripted-event</b> fake. A test declares, per step
 * index, the chunks the step "prints" and the {@link StepResult} it ends with; this replays exactly
 * that against the listener and returns it.
 *
 * <p><b>It performs no step semantics whatsoever</b> — no processes, no {@code bash}, no clone.
 * That is the point rather than a shortcut: a step's script is repo-controlled code, qits-ci never
 * executes any, and a fake that kept the shape of executing one would keep the retired approach
 * alive in the test sources after it was deleted from the main ones. Real step semantics are proven
 * in exactly one place — {@code CiDaemonGateIT}, against a real container.
 */
@Mock
@ApplicationScoped
public class FakeCiStepRunner implements CiStepRunner {

  /** What a scripted step emits before it answers. */
  public record Script(List<String> chunks, StepResult result) {

    public static Script of(StepResult result, String... chunks) {
      return new Script(List.of(chunks), result);
    }
  }

  // Accessed via executed() — a direct field read through the CDI client proxy would see the
  // proxy's own (empty) field, not the contextual instance's.
  private final List<StepSpec> executed = new ArrayList<>();
  private final List<String> emitted = new ArrayList<>();
  private final Map<Integer, Script> scripted = new HashMap<>();
  private final Map<Integer, RuntimeException> failures = new HashMap<>();
  private final Map<Integer, Consumer<StepSpec>> during = new HashMap<>();
  private final List<String> cancelled = new ArrayList<>();
  private final List<String> closed = new ArrayList<>();

  private String daemonVersion = "fake-daemon";

  public List<StepSpec> executed() {
    return executed;
  }

  /** Every chunk this fake handed to a listener, in order — the seam's event half. */
  public List<String> emitted() {
    return emitted;
  }

  public List<String> cancelled() {
    return cancelled;
  }

  public List<String> closed() {
    return closed;
  }

  public void script(int stepIndex, StepResult result) {
    scripted.put(stepIndex, Script.of(result));
  }

  public void script(int stepIndex, Script script) {
    scripted.put(stepIndex, script);
  }

  public void pin(String version) {
    daemonVersion = version;
  }

  /**
   * Makes the step blow up instead of returning — stands in for a transient infrastructure error.
   */
  public void throwOn(int stepIndex, RuntimeException failure) {
    failures.put(stepIndex, failure);
  }

  /**
   * Run something on the worker thread <b>while</b> this step is executing — after it started, before
   * it answers. It is how a test stages the events that only exist mid-step (a cancellation arriving
   * from an HTTP thread, above all) without a sleep and without a race: the run really is {@code
   * RUNNING} and really is on this step at that instant.
   */
  public void during(int stepIndex, Consumer<StepSpec> action) {
    during.put(stepIndex, action);
  }

  public void reset() {
    executed.clear();
    emitted.clear();
    scripted.clear();
    failures.clear();
    during.clear();
    cancelled.clear();
    closed.clear();
    daemonVersion = "fake-daemon";
  }

  @Override
  public DaemonPin pinDaemon() {
    return new DaemonPin(daemonVersion, "http://fake.invalid/ci-daemon/" + daemonVersion);
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    executed.add(spec);
    RuntimeException failure = failures.get(spec.stepIndex());
    if (failure != null) {
      throw failure;
    }
    Script script = scripted.getOrDefault(spec.stepIndex(), greenStep(spec.stepIndex()));
    listener.onStarted();
    Consumer<StepSpec> midStep = during.get(spec.stepIndex());
    if (midStep != null) {
      midStep.accept(spec);
    }
    for (String chunk : script.chunks()) {
      emitted.add(chunk);
      listener.onChunk(chunk);
    }
    listener.onFinished();
    return script.result();
  }

  @Override
  public void cancel(String runId) {
    cancelled.add(runId);
  }

  @Override
  public void runClosed(String runId) {
    closed.add(runId);
  }

  private static Script greenStep(int stepIndex) {
    String text = "ok step " + stepIndex;
    return Script.of(new StepResult(0, false, StepOutcome.OK, text), text);
  }
}
