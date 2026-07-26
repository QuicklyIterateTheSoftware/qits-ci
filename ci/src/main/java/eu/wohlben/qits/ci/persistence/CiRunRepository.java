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
}
