package eu.wohlben.qits.ci.control;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The step-runner seam for the service suite — a <b>scripted-event</b> fake, the same shape the ci
 * module's copy has. Duplicated on purpose: the two modules do not share a test classpath.
 *
 * <p>It used to be a deliberately <em>honest</em> fake that performed the real step semantics as
 * host processes — clone the repo at the pushed sha, then {@code bash -c <script>}. That died with
 * the approach it modelled. Running a repository's script as a host process is precisely the thing
 * qits-ci does not do, and a fixture that kept doing it would have kept the retired approach alive
 * in the test sources after it was deleted from the main ones — the residue the eradication decision
 * exists to forbid. **No fake in this repository executes a step.** Real step semantics are proven
 * in exactly one place, {@code CiDaemonGateIT}, against a real container running a real daemon.
 *
 * <p>What a test scripts here is therefore only what the seam promises: some chunks, then a result.
 *
 * <p><b>It is on by default and off for the gate.</b> A {@code @Mock} alternative replaces its bean
 * across the whole test application, which would have made {@code CiDaemonGateIT} assert against
 * this class instead of against real containers — silently, and very fast. The build property below
 * is what lets that one test profile switch it off; {@code enableIfMissing} keeps every other suite
 * exactly as it was, and the condition is build-time because that is when a bean is removed.
 */
@Mock
@ApplicationScoped
@IfBuildProperty(name = FakeCiStepRunner.ENABLED, stringValue = "true", enableIfMissing = true)
public class FakeCiStepRunner implements CiStepRunner {

  /** Set to {@code false} by a test profile that wants the real {@code CiDaemonStepRunner}. */
  public static final String ENABLED = "qits.ci.fake-step-runner";

  /** What a scripted step emits before it answers. */
  public record Script(List<String> chunks, StepResult result) {

    public static Script of(StepResult result, String... chunks) {
      return new Script(List.of(chunks), result);
    }
  }

  // Accessed via the getters — a direct field read through the CDI client proxy would see the
  // proxy's own (empty) field, not the contextual instance's.
  private final List<StepSpec> executed = new ArrayList<>();
  private final Map<Integer, Script> scripted = new HashMap<>();
  private final Map<Integer, Consumer<StepSpec>> during = new HashMap<>();
  private final List<String> cancelled = new ArrayList<>();

  // Written on the worker thread and read on the request thread — the same crossing the real
  // runner's in-flight map makes, and the reason this one is concurrent.
  private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

  public List<StepSpec> executed() {
    return executed;
  }

  public List<String> cancelled() {
    return cancelled;
  }

  public void script(int stepIndex, StepResult result, String... chunks) {
    scripted.put(stepIndex, Script.of(result, chunks));
  }

  /**
   * Run something on the run worker <b>while</b> this step is executing — the same hook the ci
   * module's copy carries, and it is here for the same reason: it is how a test stages the states
   * that only exist while a run is in flight without a sleep and without a race.
   *
   * <p>At this level that is what makes {@code RUNNING} and {@code QUEUED} observable over HTTP. The
   * worker is single-threaded, so a run parked inside its first step really does hold the next one
   * in the queue, and the read surface really is being asked about that instant.
   */
  public void during(int stepIndex, Consumer<StepSpec> action) {
    during.put(stepIndex, action);
  }

  public void reset() {
    executed.clear();
    scripted.clear();
    during.clear();
    cancelled.clear();
    inFlight.clear();
  }

  @Override
  public DaemonPin pinDaemon() {
    return new DaemonPin("fake-daemon", "http://fake.invalid/ci-daemon/fake-daemon");
  }

  @Override
  public StepResult run(StepSpec spec, StepListener listener) {
    inFlight.add(spec.runId());
    try {
      return runStep(spec, listener);
    } finally {
      inFlight.remove(spec.runId());
    }
  }

  private StepResult runStep(StepSpec spec, StepListener listener) {
    executed.add(spec);
    Script script = scripted.getOrDefault(spec.stepIndex(), green(spec.stepIndex()));
    listener.onStarted();
    Consumer<StepSpec> midStep = during.get(spec.stepIndex());
    if (midStep != null) {
      midStep.accept(spec);
    }
    for (String chunk : script.chunks()) {
      listener.onChunk(chunk);
    }
    listener.onFinished();
    return script.result();
  }

  @Override
  public void cancel(String runId) {
    cancelled.add(runId);
  }

  /** True exactly while a step of the run is executing here — what the real runner answers. */
  @Override
  public boolean owns(String runId) {
    return inFlight.contains(runId);
  }

  @Override
  public void runClosed(String runId) {
    // nothing is held between runs
  }

  private static Script green(int stepIndex) {
    String text = "step " + stepIndex + " ran";
    return Script.of(new StepResult(0, false, StepOutcome.OK, text), text);
  }
}
