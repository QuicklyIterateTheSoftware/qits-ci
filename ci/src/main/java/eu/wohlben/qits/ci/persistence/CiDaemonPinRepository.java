package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiDaemonPin;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link CiDaemonPin} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiDaemonPinRepository implements PanacheRepositoryBase<CiDaemonPin, String> {

  /** Every adopted candidate, newest event first -- the ladder walk order. */
  public List<CiDaemonPin> listNewestFirst() {
    return list("order by occurredAt desc, id desc");
  }

  /** The idempotency lookup adoption upserts on: a redelivered event is a no-op, never a second
   *  row. */
  public Optional<CiDaemonPin> findByEventId(String eventId) {
    return find("eventId", eventId).firstResultOptional();
  }

  /** Defensive: two different events must never both claim the same version. */
  public Optional<CiDaemonPin> findByVersion(String version) {
    return find("version", version).firstResultOptional();
  }

  /** The newest already-adopted candidate, or empty on a fresh ladder -- what a new candidate's
   *  {@code occurredAt} is compared against before it is adopted. */
  public Optional<CiDaemonPin> newestAdopted() {
    return find("order by occurredAt desc, id desc").firstResultOptional();
  }
}
