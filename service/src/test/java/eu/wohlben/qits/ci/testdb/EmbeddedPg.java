package eu.wohlben.qits.ci.testdb;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One real PostgreSQL for this module's whole surefire JVM.
 *
 * <p><b>Why not a container.</b> The rule that shapes everything here is that a clone of this repo
 * alone builds and tests green — no monorepo, no credentials and <em>no docker</em> — and both
 * stores this deployable boots are postgres now. Testcontainers and Quarkus dev services are therefore both out,
 * while the lineage still has to be exercised against the engine it ships on. Zonky resolves real
 * postgres binaries as ordinary Maven artifacts and this class spawns them as a child process: a
 * dependency, not a daemon.
 *
 * <p><b>The instance is tracked in a system property, not in this static field alone.</b> A Quarkus
 * test run loads config sources in more than one classloader, so a second copy of this class is
 * loaded with its own statics; the property is the one thing they share, and it is what keeps the
 * count at one postgres per JVM instead of one per classloader.
 *
 * <p>A copy of the `ci` module's class of the same name, because a test class is not a dependency
 * anything else can declare. What must never be shared is the DATABASE NAME: every (module,
 * datasource) pair names its own — and this module has TWO datasources, its own `ci` store and the
 * qits-eventstream jar's outbox — so two surefire JVMs on one host cannot mean the same database.
 * See {@link EmbeddedPgConfigSource}.
 */
public final class EmbeddedPg {

  /** Zonky's superuser. Its authentication is `trust`, so the password below is a placeholder. */
  public static final String USER = "postgres";

  /** Any string does: the embedded instance trusts local connections. Never a real credential. */
  public static final String PASSWORD = "embedded";

  /** Where the running instance's port is published for the other classloaders. */
  private static final String PORT_PROPERTY = "qits.test.embedded-pg.port";

  private static EmbeddedPostgres started;

  private EmbeddedPg() {}

  /** The port the one embedded instance listens on, starting it on the first call. */
  public static synchronized int port() {
    String recorded = System.getProperty(PORT_PROPERTY);
    if (recorded != null) {
      return Integer.parseInt(recorded);
    }
    try {
      started = EmbeddedPostgres.builder().start();
    } catch (Exception e) {
      throw new IllegalStateException("could not start the embedded postgres", e);
    }
    System.setProperty(PORT_PROPERTY, String.valueOf(started.getPort()));
    Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedPg::stop, "embedded-pg-stop"));
    return started.getPort();
  }

  /** A JDBC url for the named database on the embedded instance, creating it if it is new. */
  public static synchronized String url(String database) {
    String url = "jdbc:postgresql://localhost:" + port() + "/" + database;
    ensureDatabase(database);
    return url;
  }

  private static String adminUrl() {
    return "jdbc:postgresql://localhost:" + port() + "/postgres";
  }

  private static void ensureDatabase(String database) {
    try (Connection admin = DriverManager.getConnection(adminUrl(), USER, PASSWORD);
        Statement sql = admin.createStatement()) {
      try (ResultSet found =
          sql.executeQuery("select 1 from pg_database where datname = '" + database + "'")) {
        if (found.next()) {
          return;
        }
      }
      sql.execute("create database " + database);
    } catch (Exception e) {
      throw new IllegalStateException("could not create the test database " + database, e);
    }
  }

  private static synchronized void stop() {
    if (started != null) {
      try {
        started.close();
      } catch (Exception e) {
        // A JVM on its way out; a postgres that outlives it by a moment is not worth a stack trace.
      }
      started = null;
    }
  }
}
