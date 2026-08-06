package eu.wohlben.qits.ci.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.entity.CiRunStatus;
import eu.wohlben.qits.ci.entity.CiStepStatus;
import eu.wohlben.qits.ci.entity.CiTriggerType;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>What the schema looks like once the lineage has run, and it is a statement about H2 rather than
 * about the domain.</b> H2 2.4.240 keeps a checked IN-set tied to the session that compiled it, so a
 * surviving CHECK on {@code ci_run} or {@code ci_step} fails valid writes with 23514 as soon as the
 * pool retires that session — which on a freshly bootstrapped platform killed every run, step-less,
 * after a few long builds. V5 dropped the checks it could name and
 * {@link V9__drop_generated_check_constraints} drops the ones H2 named itself.
 *
 * <p>The reason this is asserted rather than read off a migration: the generated names differ
 * between databases, so "V5 dropped it" was true of the constraints in the script and false of the
 * database. A count of what is actually there cannot be wrong in that way.
 */
@QuarkusTest
public class CiSchemaCheckConstraintsTest {

  @Inject
  @DataSource("ci")
  AgroalDataSource ci;

  @Test
  public void noCheckConstraintSurvivesOnCiRunOrCiStep() throws SQLException {
    assertEquals(List.of(), constraints("CI_RUN", "CHECK"));
    assertEquals(List.of(), constraints("CI_STEP", "CHECK"));
  }

  @Test
  public void everyConstraintThatCarriesARealGuaranteeSurvives() throws SQLException {
    // Only duplicated enum domains are removed. The dedupe constraint is the trigger engine's
    // at-most-one-run-per-(event, trigger file) guarantee and the FK is ci's own referential
    // integrity — a migration that swept the table clean would have taken both.
    assertEquals(1, constraints("CI_RUN", "PRIMARY KEY").size());
    assertEquals(1, constraints("CI_STEP", "PRIMARY KEY").size());
    assertTrue(constraints("CI_RUN", "UNIQUE").contains("UQ_CI_RUN_EVENT_TRIGGER"));
    assertTrue(constraints("CI_STEP", "FOREIGN KEY").contains("FK_CI_STEP_RUN"));
  }

  @Test
  public void v8sNamedVerdictCheckIsLeftWhereItIs() throws SQLException {
    // Same H2 defect, different table, and deliberately not swept up: ck_ci_daemon_pin_verdict is
    // NAMED, so the day it bites it costs one line of SQL. Dropping every check in the schema would
    // also drop the next one somebody adds on purpose.
    assertEquals(
        List.of("CK_CI_DAEMON_PIN_VERDICT"), constraints("CI_DAEMON_PIN", "CHECK"));
  }

  @Test
  public void everyStatusTheEnumsCanWriteInserts() throws SQLException {
    // The invariant the dropped checks duplicated now lives only in CiRunStatus/CiStepStatus. This
    // writes the whole of both domains and rolls back, so the schema is proven to accept what the
    // enums produce rather than assumed to.
    try (Connection connection = ci.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement run =
          connection.prepareStatement(
              "insert into ci_run (id, repo_id, branch, commit_sha, status, created_at,"
                  + " trigger_type, config_path) values (?, 'schema-probe', 'main', '0', ?,"
                  + " current_timestamp, ?, '.config/qits/ci-post-receive.yml')")) {
        int i = 0;
        for (CiRunStatus status : CiRunStatus.values()) {
          for (CiTriggerType trigger : CiTriggerType.values()) {
            run.setString(1, "schema-probe-" + i++);
            run.setString(2, status.name());
            run.setString(3, trigger.name());
            run.executeUpdate();
          }
        }
      }
      try (PreparedStatement step =
          connection.prepareStatement(
              "insert into ci_step (id, run_id, step_index, image, status)"
                  + " values (?, 'schema-probe-0', 0, 'alpine:3', ?)")) {
        int i = 0;
        for (CiStepStatus status : CiStepStatus.values()) {
          step.setString(1, "schema-probe-step-" + i++);
          step.setString(2, status.name());
          step.executeUpdate();
        }
      }
      connection.rollback();
    }
  }

  private List<String> constraints(String table, String type) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = ci.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select constraint_name from information_schema.table_constraints"
                    + " where table_schema = ? and table_name = ? and constraint_type = ?"
                    + " order by constraint_name")) {
      query.setString(1, connection.getSchema());
      query.setString(2, table);
      query.setString(3, type);
      try (ResultSet found = query.executeQuery()) {
        while (found.next()) {
          names.add(found.getString(1));
        }
      }
    }
    return names;
  }
}
