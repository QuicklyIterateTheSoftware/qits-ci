package eu.wohlben.qits.ci.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply — {@code jdbc.url}, {@code username}, {@code password} — for
 * <b>both</b> datasources this deployable boots.
 *
 * <p>Two of them, because joining the qits-eventstream jar is what turns three library modules into
 * one deployable that publishes and listens: the outbox arrives with its own datasource, its own
 * persistence unit and its own Flyway lineage, and being dark in {@code %test} does not stop any of
 * that. {@code qits.eventstream.enabled=false} stops publishing, sweeping and dialling; Quarkus
 * still opens the connection and migrates at boot. So the outbox gets a database here or the suite
 * does not start.
 *
 * <p>It is a config source rather than six lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over the shipped defaults in
 * both jars (100) and anything the test properties file might carry, and it is registered through
 * {@code META-INF/services}, which is how a config source joins a Quarkus application without being
 * a bean.
 *
 * <p>What it supplies are the same keys the two shipped files resolve from {@code
 * QITS_RESOURCE_DB_*} and {@code QITS_RESOURCE_EVENTSTREAM_*}. The suite sets the VALUES rather than
 * the variables on purpose: the shipped expressions have no defaults, and a test run that also had
 * to export environment variables would be a test run that could not say what happens when they are
 * missing. {@code CiPackagedSurfaceIT} is where the shipped expressions themselves are exercised, by
 * handing the launched artifact the variables.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /**
   * This module's own store. Named per (module, datasource) pair: the {@code ci} module's suite uses
   * {@code ci_domain}, so two surefire JVMs on one host cannot mean the same database.
   */
  private static final String CI_DATABASE = "ci_svc";

  /**
   * The outbox's store. Named for this module too, and deliberately NOT {@code eventstream_test} —
   * that is the qits-eventstream library's own suite's database, and a consumer must not be able to
   * mean it.
   */
  private static final String EVENTSTREAM_DATABASE = "eventstream_svc";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.ci.jdbc.url", EmbeddedPg.url(CI_DATABASE),
          "quarkus.datasource.ci.username", EmbeddedPg.USER,
          "quarkus.datasource.ci.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
