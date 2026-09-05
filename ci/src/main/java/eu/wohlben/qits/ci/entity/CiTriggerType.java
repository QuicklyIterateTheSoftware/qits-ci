package eu.wohlben.qits.ci.entity;

/**
 * What caused a run to exist. Recorded on every row rather than inferred from whether {@code
 * triggerEventId} is null, so a column that says which trigger ran stays true if a third one is ever
 * added.
 *
 * <p><b>Only {@link #EVENT} is written any more</b>, and {@link #POST_RECEIVE} is kept for the
 * reason a catalogue value always is: rows carry it. The push intake retired on 2026-09-05 — an
 * ordinary push triggers nothing — and dropping the constant would make every historical row
 * unreadable, since the column is {@code @Enumerated(EnumType.STRING)} and the value is the string
 * in the database. It is history rather than a mode: nothing selects on it, and no code path can
 * produce it.
 */
public enum CiTriggerType {

  /**
   * A push: the git host's post-receive hook named a commit, and the run was about that commit.
   * <b>History only</b> — see the type javadoc.
   */
  POST_RECEIVE,

  /**
   * A domain event matched a {@code .config/qits/ci-event-*.yml} the repository committed. The run
   * is about the head of {@code main} at the moment the event arrived, and {@code triggerEventId} is
   * the event that caused it — which is also what its own {@code BuildSuccessful} records as parent.
   */
  EVENT
}
