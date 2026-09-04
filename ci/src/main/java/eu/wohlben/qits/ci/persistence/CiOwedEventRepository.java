package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiOwedEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * Panache DAO for {@link CiOwedEvent} (keyed by the event's own id).
 *
 * <p>Three operations and no more: an accept writes one, an evaluation deletes one, and a sweep
 * lists what a process left behind. Everything else about the ledger is {@code
 * CiEventTriggerService}'s.
 */
@ApplicationScoped
public class CiOwedEventRepository implements PanacheRepositoryBase<CiOwedEvent, String> {

  /**
   * Records one accepted event, or leaves the existing row exactly as it is.
   *
   * <p><b>Read first, then insert</b>, {@code CiScmRelease}'s arrangement: a redelivery reaching the
   * accept again must find its own row rather than write a second, and the primary key underneath is
   * what survives the race a read cannot. The caller owns the transaction, because what makes this
   * row worth writing is that it commits <em>before</em> the acceptance is reported.
   *
   * @return true when this call wrote the row, false when it was already there
   */
  public boolean record(String eventId, String eventName, Instant occurredAt, String payload) {
    if (findById(eventId) != null) {
      return false;
    }
    CiOwedEvent owed = new CiOwedEvent();
    owed.eventId = eventId;
    owed.eventName = eventName;
    owed.occurredAt = occurredAt;
    owed.payload = payload;
    owed.acceptedAt = Instant.now();
    persist(owed);
    // Flushed here rather than at commit, so a duplicate key is this method's answer rather than a
    // failure the caller sees only as its transaction refusing to commit.
    flush();
    return true;
  }

  /** Settles one event: it has been evaluated, so nothing is owed for it any more. */
  public void forget(String eventId) {
    deleteById(eventId);
  }

  /**
   * What was accepted before {@code cutoff} and never evaluated, oldest first.
   *
   * <p>The cutoff is the whole of the difference between the two sweeps: at boot it is {@code now}
   * (the deployment is stop-first, so every row is a previous process's), and on the schedule it is
   * {@code now} minus the grace, so a row this process is still evaluating is never re-offered to
   * itself.
   */
  public List<CiOwedEvent> listAcceptedBefore(Instant cutoff) {
    return list("acceptedAt < ?1 order by acceptedAt", cutoff);
  }
}
