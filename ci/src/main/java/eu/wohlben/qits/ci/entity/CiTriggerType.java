package eu.wohlben.qits.ci.entity;

/**
 * What caused a run to exist. Recorded on every row rather than inferred from whether {@code
 * triggerEventId} is null: the two are the same fact today, and a column that says which trigger ran
 * stays true if a third one is ever added.
 */
public enum CiTriggerType {

  /** A push: the git host's post-receive hook named a commit, and the run is about that commit. */
  POST_RECEIVE,

  /**
   * A domain event matched a {@code .config/qits/ci-event-*.yml} the repository committed. The run
   * is about the head of {@code main} at the moment the event arrived, and {@code triggerEventId} is
   * the event that caused it — which is also what its own {@code BuildSuccessful} records as parent.
   */
  EVENT
}
