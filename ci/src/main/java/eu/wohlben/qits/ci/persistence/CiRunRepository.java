package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiRun;
import eu.wohlben.qits.ci.entity.CiRunStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link CiRun} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiRunRepository implements PanacheRepositoryBase<CiRun, String> {

  private static final String NEWEST_FIRST = "repoId = ?1 order by createdAt desc, id desc";

  private static final String NEWEST_FIRST_ON_BRANCH =
      "repoId = ?1 and branch = ?2 order by createdAt desc, id desc";

  /** All runs recorded for a repository, newest-first. */
  public List<CiRun> listByRepoIdNewestFirst(String repoId) {
    return list(NEWEST_FIRST, repoId);
  }

  /**
   * The newest {@code limit} runs recorded for a repository.
   *
   * <p>The bound is applied in SQL rather than to a materialised list, because the point of asking
   * for the newest hundred is not fetching the other nine thousand. It is a total answer only
   * because the ordering is: {@code createdAt desc, id desc} is a strict total order over the rows,
   * so "the newest n" names the same n rows on every call. Deliberately <b>no offset</b> — a list
   * that grows at the head cannot be walked by skipping from the front without re-showing rows.
   */
  public List<CiRun> listByRepoIdNewestFirst(String repoId, int limit) {
    return find(NEWEST_FIRST, repoId).range(0, limit - 1).list();
  }

  /**
   * The newest run recorded for a repository on any branch, or empty when it has none — the {@code
   * lastRun} half of {@code GET /ci/api/repositories/summary}.
   */
  public Optional<CiRun> newestFor(String repoId) {
    return find(NEWEST_FIRST, repoId).firstResultOptional();
  }

  /**
   * The newest run recorded for a repository on one branch, or empty when it has none — the {@code
   * lastMainRun} half of the same summary.
   *
   * <p>A separate query rather than a filter over {@link #newestFor}'s answer, because the two
   * questions have different answers and the second one's answer can be arbitrarily far down the
   * list: a repository with a hundred feature-branch runs since its last push to {@code main} would
   * need the whole history read to find it.
   */
  public Optional<CiRun> newestForBranch(String repoId, String branch) {
    return find(NEWEST_FIRST_ON_BRANCH, repoId, branch).firstResultOptional();
  }

  /**
   * Every run that is accepted but not finished — {@code QUEUED} or {@code RUNNING} — across all
   * repositories, newest-first. The read behind {@code GET /ci/api/runs/active}.
   *
   * <p>Unscoped by repository on purpose, and it is the one read on this surface that is: the
   * question it answers is "what is CI doing right now", which has no repository to scope to. It is
   * bounded by accepted work and the configured worker pool rather than by a limit, so it does
   * not carry one.
   */
  public List<CiRun> listActiveNewestFirst() {
    return list(
        "status in (?1, ?2) order by createdAt desc, id desc",
        CiRunStatus.QUEUED,
        CiRunStatus.RUNNING);
  }

  /**
   * The newest {@code limit} runs that are over — anything not {@code QUEUED} or {@code RUNNING} —
   * across all repositories, newest-first. The read behind {@code GET /ci/api/runs/finished}.
   *
   * <p><b>The predicate is the complement of {@link #listActiveNewestFirst}'s, and that is the
   * point.</b> Naming the terminal statuses instead ({@code in (SUCCESS, FAILED, CANCELLED,
   * CONFIG_ERROR, TIMED_OUT)})
   * would read the same today and rot silently: another status added to {@code ck_ci_run_status}
   * would be finished in fact and invisible here, so a run would leave the active list and never
   * arrive in this one. Written as a complement, the two lists partition the table by construction
   * and a new status is finished by default — which is also how {@code isTerminal} is defined on both
   * sides of the wire.
   *
   * <p>Unscoped by repository, like the active list and unlike everything else here, because "what
   * did CI last finish" has no repository to scope to. Unlike the active list it <b>needs</b> its
   * bound: what is active is bounded by what one worker has accepted, while what is finished grows
   * with the life of the instance. Ordering is by {@code finishedAt}, not acceptance time: a slow
   * older pipeline that finishes now belongs ahead of a newer pipeline that completed earlier.
   */
  public List<CiRun> listFinishedNewestFirst(int limit) {
    return find(
            "status not in (?1, ?2) order by finishedAt desc, id desc",
            CiRunStatus.QUEUED,
            CiRunStatus.RUNNING)
        .range(0, limit - 1)
        .list();
  }

  /**
   * Every run left {@code QUEUED} by a previous process, oldest-first — what the startup sweep
   * re-enqueues, in the order the runs were accepted so a restart does not reorder a backlog.
   */
  public List<CiRun> listQueuedOldestFirst() {
    return list("status = ?1 order by createdAt, id", CiRunStatus.QUEUED);
  }

  /** Older queued push builds superseded by a newly accepted push on the same branch. */
  public List<CiRun> listQueuedPushes(String repoId, String branch, String exceptRunId) {
    return list(
        "repoId = ?1 and branch = ?2 and status = ?3 and triggerType = ?4 and id <> ?5",
        repoId,
        branch,
        CiRunStatus.QUEUED,
        eu.wohlben.qits.ci.entity.CiTriggerType.POST_RECEIVE,
        exceptRunId);
  }

  /**
   * The other queued runs one trigger file has for one event name, the versionsort supersede's
   * candidates — see {@code CiRunService.supersedeByVersion}.
   *
   * <p>Scoped by {@code configPath} rather than by branch, which is the event path's answer to the
   * same question {@link #listQueuedPushes} asks: a push is one pipeline per branch, and a trigger
   * file is one pipeline per file. The event NAME is in the predicate because two trigger files can
   * be one file at two names only by coincidence — what supersedes what is decided by reading both
   * payloads, and two payloads of different events have no field in common to compare.
   */
  public List<CiRun> listQueuedEventRuns(
      String repoId, String configPath, String eventName, String exceptRunId) {
    return list(
        "repoId = ?1 and configPath = ?2 and status = ?3 and triggerType = ?4"
            + " and triggerEventName = ?5 and id <> ?6",
        repoId,
        configPath,
        CiRunStatus.QUEUED,
        eu.wohlben.qits.ci.entity.CiTriggerType.EVENT,
        eventName,
        exceptRunId);
  }

  /**
   * {@link #listQueuedEventRuns} narrowed to one branch — the checkout collapse's question, which
   * only makes sense for runs whose branch came out of the payload (the caller gates on that): a
   * burst of pushes to one branch is one pipeline per file per branch, {@link #listQueuedPushes}'
   * question on the event path.
   */
  public List<CiRun> listQueuedEventRunsOnBranch(
      String repoId, String configPath, String eventName, String branch, String exceptRunId) {
    return list(
        "repoId = ?1 and configPath = ?2 and status = ?3 and triggerType = ?4"
            + " and triggerEventName = ?5 and branch = ?6 and id <> ?7",
        repoId,
        configPath,
        CiRunStatus.QUEUED,
        eu.wohlben.qits.ci.entity.CiTriggerType.EVENT,
        eventName,
        branch,
        exceptRunId);
  }

  /**
   * Every unfinished run one repository has for one release request — {@code QUEUED} or {@code
   * RUNNING} — oldest first. What {@code POST /ci/api/runs/cancellations} cancels.
   *
   * <p><b>The pair is the scope, and both halves are load-bearing.</b> A release request is
   * qits-projects' aggregate and one request folds N repositories, so the id alone would reach
   * another repository's run; a repository alone would reach the runs of every OTHER request open
   * against it, which is exactly the sibling a withdrawn request must not take down with it. The
   * predicate is the complement of the terminal statuses for {@link #listFinishedNewestFirst}'s
   * reason: a status added later is finished by default rather than silently cancellable.
   *
   * <p>Oldest first rather than newest, unlike every other listing here, because this one is not
   * read — it is <em>acted on</em>, run by run, and settling the oldest first is what makes a
   * partial pass (a caller that died halfway) leave the newest work standing rather than the
   * staleest.
   */
  public List<CiRun> listUnfinishedForReleaseRequest(String repoId, String releaseRequestId) {
    return list(
        "repoId = ?1 and releaseRequestId = ?2 and status in (?3, ?4) order by createdAt, id",
        repoId,
        releaseRequestId,
        CiRunStatus.QUEUED,
        CiRunStatus.RUNNING);
  }

  /**
   * Every repository this instance has ever recorded a run for — half of what {@code KnownCiRepos}
   * offers the trigger engine as candidates, and the whole of what {@code GET /ci/api/repositories}
   * answers.
   *
   * <p>Unsorted here on purpose: the trigger engine drops the result into a {@code TreeSet} and the
   * read surface sorts for its own reasons, so a database-side {@code order by} would be a third
   * opinion about an ordering neither caller takes from here.
   */
  public List<String> distinctRepoIds() {
    return getEntityManager()
        .createQuery("select distinct r.repoId from CiRun r", String.class)
        .getResultList();
  }

  /**
   * Whether this (event, repository, trigger file) has already produced a run.
   *
   * <p>A <b>cheap pre-check, not the guarantee</b>. The guarantee is the unique constraint on
   * {@code (trigger_event_id, repo_id, config_path)}, which is what survives a race and a restart;
   * this query only keeps a redelivery from reaching the insert and turning an expected outcome into
   * a caught exception in the log.
   */
  public boolean alreadyTriggered(String triggerEventId, String repoId, String configPath) {
    return count(
            "triggerEventId = ?1 and repoId = ?2 and configPath = ?3",
            triggerEventId,
            repoId,
            configPath)
        > 0;
  }
}
