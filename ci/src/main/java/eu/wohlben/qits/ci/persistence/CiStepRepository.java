package eu.wohlben.qits.ci.persistence;

import eu.wohlben.qits.ci.entity.CiStep;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link CiStep} (keyed by its String UUID row id). */
@ApplicationScoped
public class CiStepRepository implements PanacheRepositoryBase<CiStep, String> {

  /** A run's steps in declaration order. */
  public List<CiStep> listByRunIdOrdered(String runId) {
    return list("runId = ?1 order by stepIndex", runId);
  }
}
