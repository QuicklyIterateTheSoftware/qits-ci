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
 * <b>What the schema looks like once the lineage has run, against the engine it ships on.</b> The
 * migration is applied to a real PostgreSQL (see {@code testdb/EmbeddedPg}), so what is asserted
 * here is the shipped {@code V1__init.sql} rather than a description of it.
 *
 * <p>It exists because the check constraints are a set of DECISIONS, each argued in that file's
 * header, and postgres would permit every one of them. Two of those decisions used to be
 * self-enforcing: the H2 lineage removed the enum checks in V5 and in a java V9 that read
 * INFORMATION_SCHEMA, because H2 2.4.240 tied a compiled IN-set to the session that made it and
 * failed valid writes with 23514 — on a freshly bootstrapped platform that killed every run,
 * step-less. That defect is gone with H2 and with the migrations that answered it, and what remains
 * is a choice nothing in the code would notice being reversed. So it is pinned.
 *
 * <p>Read out of {@code pg_constraint} and not {@code information_schema.table_constraints}: postgres
 * lists a NOT NULL as a CHECK row there, so "no check constraints" would be unassertable through the
 * standard view. {@code contype = 'c'} is the real question.
 */
@QuarkusTest
public class CiSchemaTest {

  @Inject
  @DataSource("ci")
  AgroalDataSource ci;

  @Test
  public void noCheckConstraintGuardsAnEnumColumn() throws SQLException {
    // ci_run.status, ci_run.trigger_type and ci_step.status are catalogues that have grown once
    // already — V4 added QUEUED, and EVENT joined POST_RECEIVE — so the invariant lives where the
    // writes are: CiRunStatus, CiTriggerType and CiStepStatus are @Enumerated(STRING) and no code
    // path writes a status any other way. A database that also enumerated them would be a second
    // list to keep in step.
    assertEquals(List.of(), constraints("ci_run", 'c'));
    assertEquals(List.of(), constraints("ci_step", 'c'));
  }

  @Test
  public void theVerdictCheckIsTheOneThatStays() throws SQLException {
    // The other way round, and deliberately: a verdict is a closed statement about one probe's
    // outcome with an UNKNOWN arm already in it, so it is an invariant rather than a catalogue. It
    // is also NAMED, which is what the two anonymous inline checks in the H2 V1 were not — the day
    // it ever needs widening costs one line of SQL with nothing to measure.
    assertEquals(List.of("ck_ci_daemon_pin_verdict"), constraints("ci_daemon_pin", 'c'));
  }

  @Test
  public void everyConstraintThatCarriesARealGuaranteeIsThere() throws SQLException {
    // The dedupe constraint is the trigger engine's at-most-one-run-per-(event, trigger file)
    // guarantee and the FK is ci's own referential integrity. A translation that had swept the
    // schema clean of constraints would have taken both.
    assertEquals(1, constraints("ci_run", 'p').size());
    assertEquals(1, constraints("ci_step", 'p').size());
    assertEquals(1, constraints("ci_daemon_pin", 'p').size());
    assertTrue(constraints("ci_run", 'u').contains("uq_ci_run_event_trigger"));
    assertTrue(constraints("ci_daemon_pin", 'u').contains("uq_ci_daemon_pin_version"));
    assertTrue(constraints("ci_step", 'f').contains("fk_ci_step_run"));
  }

  @Test
  public void everyStatusTheEnumsCanWriteInserts() throws SQLException {
    // The invariant the absent checks would have duplicated lives only in the enums now. This writes
    // the whole of all three domains and rolls back, so the schema is proven to accept what the
    // enums produce rather than assumed to.
    try (Connection connection = ci.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement run =
          connection.prepareStatement(
              "insert into ci_run (id, repo_id, branch, commit_sha, gating, status, created_at,"
                  + " trigger_type, config_path) values (?, 'schema-probe', 'main', '0', true, ?,"
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

  @Test
  public void theUnboundedColumnsAreTextAndNotLargeObjects() throws SQLException {
    // The one entity mapping the move had to change. On postgres @Lob means a LARGE OBJECT —
    // Hibernate binds an oid — and a column that came out `oid` here would fail every step write at
    // runtime while this suite stayed green about everything else.
    assertEquals("text", columnType("ci_step", "output"));
    assertEquals("text", columnType("ci_run", "trigger_event_payload"));
    assertEquals("text", columnType("ci_run", "trigger_config"));
    assertEquals("text", columnType("ci_daemon_pin", "detail"));
  }

  @Test
  public void theRepositoryIdentityColumnsAreNullableAndTakeBothArms() throws SQLException {
    // V5. Both halves of the public coordinate are optional BY DESIGN: an id-addressed push
    // announces neither, and no historical row has them, so a not-null column would have needed a
    // value nobody pushed. Written and rolled back rather than described, so the lineage is proven
    // to accept a run with names and one without.
    assertEquals("character varying", columnType("ci_run", "project_id"));
    assertEquals("character varying", columnType("ci_run", "repo_name"));
    try (Connection connection = ci.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement run =
          connection.prepareStatement(
              "insert into ci_run (id, repo_id, project_id, repo_name, branch, commit_sha, gating,"
                  + " status, created_at, trigger_type, config_path) values (?, 'schema-probe', ?,"
                  + " ?, 'main', '0', true, 'QUEUED', current_timestamp, 'POST_RECEIVE',"
                  + " '.config/qits/ci-post-receive.yml')")) {
        run.setString(1, "identity-probe-named");
        run.setString(2, "qits");
        run.setString(3, "qits-blobstore");
        run.executeUpdate();
        run.setString(1, "identity-probe-unnamed");
        run.setString(2, null);
        run.setString(3, null);
        run.executeUpdate();
      }
      connection.rollback();
    }
  }

  private List<String> constraints(String table, char type) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection = ci.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select c.conname from pg_constraint c"
                    + " join pg_class t on t.oid = c.conrelid"
                    + " where t.relname = ? and c.contype = ?"
                    + " order by c.conname")) {
      query.setString(1, table);
      query.setString(2, String.valueOf(type));
      try (ResultSet found = query.executeQuery()) {
        while (found.next()) {
          names.add(found.getString(1));
        }
      }
    }
    return names;
  }

  private String columnType(String table, String column) throws SQLException {
    try (Connection connection = ci.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "select data_type from information_schema.columns"
                    + " where table_schema = current_schema()"
                    + " and table_name = ? and column_name = ?")) {
      query.setString(1, table);
      query.setString(2, column);
      try (ResultSet found = query.executeQuery()) {
        return found.next() ? found.getString(1) : null;
      }
    }
  }
}
