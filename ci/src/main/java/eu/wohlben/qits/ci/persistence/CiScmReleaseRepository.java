package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiScmRelease;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Panache DAO for {@link CiScmRelease} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiScmReleaseRepository implements PanacheRepositoryBase<CiScmRelease, String> {

  /**
   * Whether a {@code (repository, version)} was really released.
   *
   * <p><b>Either spelling of the repository matches</b>, and that is the whole reason this is not a
   * {@code find("repoId", …)}: the run's side of the join is the git host's repository id, while the
   * event carries an id and, optionally, the registered name. The two agree on this platform and the
   * event does not promise it, so a join that only compared one of them would silently never close.
   */
  public boolean released(String repoId, String version) {
    return count("(repoId = ?1 or repoName = ?1) and version = ?2", repoId, version) > 0;
  }

  /**
   * The recorded fact for one release, or empty — the idempotency read the insert is guarded by.
   *
   * <p>Named rather than a {@code find} overload: Panache's own {@code find(String, Object...)} would
   * take a two-string call, and which method a call site reaches is not something to leave to
   * overload resolution.
   */
  public Optional<CiScmRelease> findRelease(String repoId, String version) {
    return find("repoId = ?1 and version = ?2", repoId, version).firstResultOptional();
  }
}
