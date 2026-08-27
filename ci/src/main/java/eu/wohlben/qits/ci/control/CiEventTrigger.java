package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * One parsed {@code .config/qits/ci-event-*.yml}: the event name it listens for, the selection over
 * that event's payload, the pipeline to run when both hold, and what that pipeline publishes.
 *
 * <p>{@code artifacts} is empty for every trigger file that declares none, which is most of them —
 * an ordinary event pipeline bumps a dependency and publishes nothing. A non-empty list makes this a
 * <b>release pipeline</b>: a green run announces one {@code SoftwareRelease} per entry, carrying the
 * version out of the event that triggered it. See {@link CiArtifact}.
 *
 * <p>{@code configPath} is the file it came from — the {@code *} is freely chosen and completely
 * ignored as a selector (it names the trigger for humans), but the path itself is <b>identity</b>:
 * it is recorded on the run and it is one third of the unique constraint that makes a triggered run
 * at-most-once. Two different trigger files in one repository matching the same event are two runs
 * by design; they are two declared pipelines.
 *
 * <p>{@code checkout} is null for every file that declares none — the run then builds the head of
 * {@code main}, as every event trigger always has. Declared, the run builds <b>the commit the
 * event names</b>: the two components are dot-paths into the payload, resolved per event.
 */
public record CiEventTrigger(
    String configPath,
    String eventName,
    CiEventSelection selection,
    CiPipeline pipeline,
    List<CiArtifact> artifacts,
    Checkout checkout) {

  /** Where a run of this trigger checks out: two payload dot-paths. Null = main's head. */
  public record Checkout(String branchPath, String shaPath) {}
}
