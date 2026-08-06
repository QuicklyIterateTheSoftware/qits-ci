package eu.wohlben.qits.ci.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * <b>V5 finished only half the job, and this is the other half.</b> H2 2.4.240 keeps a checked
 * IN-set tied to the session that compiled it. Once the pool retires that session, a later write
 * fails with 23514 {@code Check constraint invalid} even though the value is valid and unchanged.
 * V5 removed the constraints it could name; this one removes the constraints nobody can name.
 *
 * <p>Why V5 missed them: it ran {@code drop constraint if exists ck_ci_run_trigger_type /
 * ck_ci_run_status / ck_ci_step_status}, but {@code V1__init.sql} declares the two status domains
 * INLINE and UNNAMED — {@code status varchar(32) not null check (status in (...))} — so H2 named
 * them itself. Two of V5's three drops therefore matched nothing and were silent no-ops. Measured
 * on a fresh replay of the lineage, what survived was {@code CI_STEP.CONSTRAINT_5A}, and it is what
 * killed every run on a freshly bootstrapped platform: after a few long builds the pool retired the
 * compiling session and every {@code insert into ci_step} failed 23514, so runs died step-less.
 *
 * <p><b>Why this has to be Java rather than one more .sql file.</b> The generated names are not
 * stable across databases — H2 derives them from an object counter, so they depend on the order the
 * DDL was replayed, and this platform's live database and a fresh one do not agree. V4 could name
 * {@code CONSTRAINT_76} because it had measured that one database; a migration that must be right
 * on <em>every</em> database cannot name anything. So it reads
 * {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} and drops whatever it finds.
 *
 * <p><b>The invariant is not lost, because the constraint was never carrying it.</b> {@code
 * CiRunStatus} and {@code CiStepStatus} are what every write goes through — the entities are {@code
 * @Enumerated(EnumType.STRING)} and no code path writes a status any other way. That is V5's
 * justification and it is unchanged: what is removed is a duplicate of an invariant Java already
 * owns, on a database engine that cannot hold it reliably.
 *
 * <p><b>Scope is exactly two tables and exactly one constraint type.</b> Only {@code ci_run} and
 * {@code ci_step}, and only {@code CHECK} — the primary keys, {@code uq_ci_run_event_trigger} (the
 * trigger engine's at-most-once guarantee) and {@code fk_ci_step_run} are untouched, and so is V8's
 * {@code ck_ci_daemon_pin_verdict} on its own table. That one is NAMED, so it can be dropped by one
 * line of SQL on the day it bites; it is left alone here rather than swept up, because a migration
 * that drops every check in the schema would also drop the next one somebody adds on purpose.
 *
 * <p>Flyway finds this class because {@code quarkus.flyway.ci.locations} names its package beside
 * the SQL directory. Quarkus filters discovered {@code JavaMigration}s by that list, which is what
 * keeps this migration off the other Flyway lineages in the same application (the eventstream
 * outbox's). Move the class, move the location.
 */
public class V9__drop_generated_check_constraints extends BaseJavaMigration {

  /** H2 upper-cases unquoted identifiers, and INFORMATION_SCHEMA answers in that case. */
  private static final List<String> TABLES = List.of("CI_RUN", "CI_STEP");

  private static final String CHECKS_ON_TABLE =
      """
      select constraint_name
        from information_schema.table_constraints
       where constraint_type = 'CHECK'
         and table_schema = ?
         and table_name = ?
      """;

  @Override
  public void migrate(Context context) throws SQLException {
    Connection connection = context.getConnection();
    for (String table : TABLES) {
      for (String constraint : checksOn(connection, table)) {
        // The name comes out of the database's own catalogue, and it is quoted because a generated
        // name is not a keyword this migration gets to assume anything about.
        try (Statement drop = connection.createStatement()) {
          drop.execute("alter table \"" + table + "\" drop constraint \"" + constraint + "\"");
        }
      }
    }
  }

  private List<String> checksOn(Connection connection, String table) throws SQLException {
    List<String> names = new ArrayList<>();
    try (PreparedStatement query = connection.prepareStatement(CHECKS_ON_TABLE)) {
      query.setString(1, connection.getSchema());
      query.setString(2, table);
      try (ResultSet found = query.executeQuery()) {
        while (found.next()) {
          names.add(found.getString(1));
        }
      }
    }
    return names;
  }
}
