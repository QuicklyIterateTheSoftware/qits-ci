package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiRun;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link CiRun} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiRunRepository implements PanacheRepositoryBase<CiRun, String> {

  /** All runs recorded for a repository, newest-first. */
  public List<CiRun> listByRepoIdNewestFirst(String repoId) {
    return list("repoId = ?1 order by createdAt desc, id desc", repoId);
  }

  /**
   * Every repository this instance has ever recorded a run for — half of what {@code KnownCiRepos}
   * offers the trigger engine as candidates.
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
