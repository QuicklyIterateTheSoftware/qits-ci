package eu.wohlben.qits.ci.control;

/**
 * One parsed {@code .config/qits/ci-event-*.yml}: the event name it listens for, the selection over
 * that event's payload, and the pipeline to run when both hold.
 *
 * <p>{@code configPath} is the file it came from — the {@code *} is freely chosen and completely
 * ignored as a selector (it names the trigger for humans), but the path itself is <b>identity</b>:
 * it is recorded on the run and it is one third of the unique constraint that makes a triggered run
 * at-most-once. Two different trigger files in one repository matching the same event are two runs
 * by design; they are two declared pipelines.
 */
public record CiEventTrigger(
    String configPath, String eventName, CiEventSelection selection, CiPipeline pipeline) {}
