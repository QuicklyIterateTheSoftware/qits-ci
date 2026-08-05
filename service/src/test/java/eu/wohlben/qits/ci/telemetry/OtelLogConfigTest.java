package eu.wohlben.qits.ci.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The drift guard on this service's OTel log export. It reads the config a run actually boots with
 * and pins the six keys that decide whether an {@code org.jboss.logging.Logger} call leaves the
 * process as an OTLP record: the four {@code quarkus.otel.logs.*} values, the exporter endpoint and
 * the http/protobuf protocol.
 *
 * <p>This test asserts CONFIGURATION and nothing else. That the keys really produce an exported
 * record is proven once, in qits-events: {@code OtelLogBridgeTest} decodes a real {@code
 * ExportLogsServiceRequest} from the running handler and {@code PackagedLogBridgeIT} repeats the
 * claim against the packaged artifact. This service runs the same {@code quarkus-opentelemetry}
 * extension on the same four keys, so the behaviour is inherited rather than re-measured, and what
 * is left to lose here is the configuration — a Quarkus upgrade changing a default, or a property
 * edit, would otherwise stop this service's logging with a green build and no error anywhere.
 *
 * <p>The values come from {@code src/main/resources/application.properties}, which the suite
 * inherits rather than shadowing, so this is the shipped config under test.
 */
@QuarkusTest
class OtelLogConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void logExportIsOnAndRidesTheConfiguredExporter() {
    // enabled turns the signal on; handler.enabled is the JBoss Log Manager handler that makes the
    // records; cdi routes them at the exporter configured below rather than a second one. All three
    // are Quarkus' own defaults, which is precisely why they are pinned.
    assertEquals("true", value("quarkus.otel.logs.enabled"));
    assertEquals("true", value("quarkus.otel.logs.handler.enabled"));
    assertEquals("cdi", value("quarkus.otel.logs.exporter"));
  }

  @Test
  void theOutboundFloorIsInfo() {
    // The one value that is not the shipped default: Quarkus exports everything the log manager
    // creates (ALL), and this narrows what leaves the process to a deliberate amount.
    assertEquals("INFO", value("quarkus.otel.logs.level"));
  }

  @Test
  void theExporterPointsAtTheReceiverOverHttpProtobuf() {
    // The SDK appends /v1/logs to this base, so it resolves to qits-observability's own ingest
    // route. gRPC is the Quarkus default and the receiver does not speak it.
    assertEquals("http/protobuf", value("quarkus.otel.exporter.otlp.protocol"));
    assertEquals(
        "http://qits-observability:8080/observability/api/otel",
        value("quarkus.otel.exporter.otlp.endpoint"));
  }
}
