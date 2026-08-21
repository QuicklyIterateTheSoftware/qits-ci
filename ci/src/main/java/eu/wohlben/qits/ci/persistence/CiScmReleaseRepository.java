package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiScmRelease;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

/** Panache DAO for {@link CiScmRelease} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiScmReleaseRepository implements PanacheRepositoryBase<CiScmRelease, String> {

  /**
   * Whether a {@code (repository, version)} was really released, asked with the storage id alone —
   * what the boot sweep has, since an owed row is keyed by the run's repository id.
   */
  public boolean released(String repoId, String version) {
    return released(repoId, null, version);
  }

  /**
   * Whether a {@code (repository, version)} was really released, asked with <b>both</b> spellings of
   * the repository the run knows.
   *
   * <p><b>The name is the preferred half and the id is the fallback</b>, which is the whole reason
   * this is not a {@code find("repoId", …)}. After the identity cutover a run's {@code repoId} is an
   * opaque storage UUID while {@code SCMRelease} announces the platform's public name, so comparing
   * ids alone would silently never close the join; before it, id and name agree and either arm
   * answers. Every combination is compared because neither side promises which spelling it carries:
   * the event records an id and, optionally, a registered name, and the run now records the same
   * pair.
   *
   * @param repoName the run's own public name, or null when its push was id-addressed
   */
  public boolean released(String repoId, String repoName, String version) {
    if (repoName == null || repoName.isBlank()) {
      return count("(repoId = ?1 or repoName = ?1) and version = ?2", repoId, version) > 0;
    }
    return count(
            "(repoName = ?1 or repoId = ?1 or repoId = ?2 or repoName = ?2) and version = ?3",
            repoName,
            repoId,
            version)
        > 0;
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
