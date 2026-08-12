package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiReleaseAnnouncement;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
import java.util.List;

/** Panache DAO for {@link CiReleaseAnnouncement} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiReleaseAnnouncementRepository
    implements PanacheRepositoryBase<CiReleaseAnnouncement, String> {

  private static final String OWED =
      "repoId = ?1 and version = ?2 and announcedAt is null order by createdAt, artifactIndex, id";

  /**
   * The announcements one {@code (repository, version)} still owes, oldest first — what the join
   * makes the moment the release fact is in.
   *
   * <p>Ordered so a pipeline's declarations are announced in the order they were declared, which is
   * the order a green run wrote them in.
   */
  public List<CiReleaseAnnouncement> listOwed(String repoId, String version) {
    return list(OWED, repoId, version);
  }

  /**
   * The same rows, locked for the transaction that will announce them — {@code select … for update}.
   *
   * <p><b>Two threads can reach one owed row</b>, because the join is driven from both ends: a green
   * run finding the release fact already in, and an {@code SCMRelease} arriving for a run that
   * finished a moment ago. Without the lock both would read it owed and announce it twice. With it,
   * the loser blocks and then re-reads — postgres re-checks the predicate after taking the lock — so
   * it finds the row already announced and has nothing to say.
   *
   * <p>The announcement is published <b>inside</b> that transaction, so the lock is held across a
   * bus publish. Bounded by the publish timeout, and it contends only with the other announcer of
   * the same {@code (repository, version)}.
   */
  public List<CiReleaseAnnouncement> lockOwed(String repoId, String version) {
    return find(OWED, repoId, version).withLock(LockModeType.PESSIMISTIC_WRITE).list();
  }

  /**
   * Every {@code (repository, version)} with something still owed — the boot sweep's candidate list.
   *
   * <p>Distinct pairs rather than rows: the sweep asks the release fact once per pair and announces
   * whatever that pair owes, so N artifacts of one run cost one lookup.
   */
  public List<Object[]> distinctOwedKeys() {
    return getEntityManager()
        .createQuery(
            "select distinct a.repoId, a.version from CiReleaseAnnouncement a"
                + " where a.announcedAt is null",
            Object[].class)
        .getResultList();
  }

  /** What one run owes or has already announced — the read a test and an operator make. */
  public List<CiReleaseAnnouncement> listForRun(String runId) {
    return list("runId = ?1 order by artifactIndex, id", runId);
  }
}
