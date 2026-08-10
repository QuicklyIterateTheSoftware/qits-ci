package eu.wohlben.qits.ci.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply: {@code jdbc.url}, {@code username}, {@code password}.
 *
 * <p>It is a config source rather than three lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both the shipped defaults
 * in this module's {@code META-INF/microprofile-config.properties} (100) and anything the test
 * properties file might carry, and it is registered through {@code META-INF/services}, which is how
 * a config source joins a Quarkus application without being a bean.
 *
 * <p>What it supplies are the same three keys the shipped file resolves from {@code
 * QITS_RESOURCE_DB_*}. The suite sets the VALUES rather than the variables on purpose: the shipped
 * expressions have no defaults, and a test run that also had to export environment variables would
 * be a test run that could not say what happens when they are missing. {@code CiPackagedSurfaceIT}
 * is where the shipped expression itself is exercised, by handing the launched artifact the
 * variables.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /**
   * This module's database on the embedded instance. Every (module, datasource) pair in this repo
   * names its own — {@code service} uses {@code ci_svc} and {@code eventstream_svc} — so two suites
   * on one host cannot mean the same database.
   */
  private static final String DATABASE = "ci_domain";

  /**
   * The qits-eventstream jar arrived in this module with {@code CausedRow} (CiRun's causation
   * column), and dark does not mean absent: its persistence unit opens a connection and runs Flyway
   * at boot whether the bus is enabled or not, so this suite feeds it a database of its own — the
   * same consumer contract the service module's copy of this class has always honoured.
   */
  private static final String EVENTSTREAM_DATABASE = "eventstream_ci_domain";

  private static final String PREFIX = "quarkus.datasource.ci.";

  private static final String EVENTSTREAM_PREFIX = "quarkus.datasource.eventstream.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD,
          EVENTSTREAM_PREFIX + "jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          EVENTSTREAM_PREFIX + "username", EmbeddedPg.USER,
          EVENTSTREAM_PREFIX + "password", EmbeddedPg.PASSWORD);

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
