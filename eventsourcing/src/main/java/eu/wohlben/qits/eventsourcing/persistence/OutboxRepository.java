package eu.wohlben.qits.eventsourcing.persistence;

import eu.wohlben.qits.eventsourcing.entity.OutboxEvent;
import eu.wohlben.qits.eventsourcing.entity.OutboxStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/** Panache DAO for {@link OutboxEvent} (keyed by the event's own UUID, as a String). */
@ApplicationScoped
public class OutboxRepository implements PanacheRepositoryBase<OutboxEvent, String> {

  /**
   * The rows the sweeper may attempt now, oldest occurrence first — so a burst that failed together
   * is retried in the order it happened rather than in whatever order the table hands back.
   */
  public List<OutboxEvent> due(Instant now, int limit) {
    return find(
            "status = ?1 and nextAttemptAt <= ?2 order by occurredAt, id",
            OutboxStatus.PENDING,
            now)
        .page(0, limit)
        .list();
  }
}
