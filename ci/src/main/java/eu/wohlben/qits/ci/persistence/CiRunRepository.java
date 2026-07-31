package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link CiRun} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiRunRepository implements PanacheRepositoryBase<CiRun, String> {

  private static final String NEWEST_FIRST = "repoId = ?1 order by createdAt desc, id desc";

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
