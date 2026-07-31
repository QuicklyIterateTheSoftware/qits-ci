package eu.wohlben.qits.eventsourcing.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The single HTTP call this module makes: {@code PUT {qits.events.url}/events/api/events/{id}} with
 * the envelope. Used twice — once inline by {@link eu.wohlben.qits.eventsourcing.QitsEventBus} and
 * again by {@link OutboxSweeper} — with identical semantics, which is what makes "a retry is the
 * same request" true rather than merely intended.
 *
 * <p><b>The three answers are three different futures</b>, and collapsing any pair loses something:
 *
 * <ul>
 *   <li><b>200 / 201</b> — delivered. 201 wrote the row, 200 found it already there with the same
 *       content. A replay is a success, not a conflict; that is the whole point of the client
 *       choosing the UUID.
 *   <li><b>400</b> — rejected, permanently. qits-events answers it when the id exists carrying
 *       <em>different</em> content, i.e. a UUID was reused. Retrying cannot change that, and a row
 *       that kept retrying it would look like an outage.
 *   <li><b>anything else</b> — retryable. A 5xx, a connection refused, a timeout, a proxy's 502:
 *       all "not now", all indistinguishable from each other from here, and all handled the same
 *       way by the outbox.
 * </ul>
 *
 * <p>The call is <b>synchronous and bounded</b>. Synchronous because the caller has to know which
 * of the three happened before it decides whether to persist anything, and bounded because the
 * publish hook sits on qits-ci's single-threaded run worker: an unbounded wait here would park
 * every pipeline on the instance behind one unreachable service. The request deadline is
 * configurable and short by default for exactly that reason — the price of qits-events being down
 * is a few seconds per green build, paid once, after which the outbox owns the problem.
 */
@ApplicationScoped
public class EventsPublisher {

  private static final Logger LOG = Logger.getLogger(EventsPublisher.class);

  /** The events API's own path under {@code qits.events.url}, which is a bare scheme+host+port. */
  static final String EVENTS_PATH = "/events/api/events/";

  /** What came of one attempt. */
  public enum Outcome {
    DELIVERED,
    REJECTED,
    RETRYABLE
  }

  /** An attempt's outcome plus the one line of detail an outbox row records. */
  public record Delivery(Outcome outcome, String detail) {

    public boolean delivered() {
      return outcome == Outcome.DELIVERED;
    }

    public boolean retryable() {
      return outcome == Outcome.RETRYABLE;
    }
  }

  /**
   * An <b>instance</b> field, not a static one: a static {@code HttpClient} is created at image
   * build time and native-image refuses the heap it lands in. {@code @ApplicationScoped} keeps it
   * one client per process. Same constraint {@code CdBuildNotifier} documents.
   *
   * <p><b>Pinned to HTTP/1.1, and that is not a preference.</b> The JDK client defaults to HTTP/2
   * with an {@code h2c} upgrade, and an upgrade that carries a request body delivers that body
   * <em>twice</em>: measured against the test stub, one PUT arrived once through the server's
   * upgrade handler and again as an HTTP/2 data frame ninety milliseconds later. Idempotency made
   * the duplicate harmless — the second delivery is a 200 replay, which is exactly what the design
   * is for — but it is a wasted round trip on every single publish, and it is the kind of thing
   * that is invisible until something counts requests. qits-events speaks plain HTTP/1.1 on
   * qits-net; there is nothing to upgrade to.
   */
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  @ConfigProperty(name = "qits.events.url")
  String eventsUrl;

  @ConfigProperty(name = "qits.eventsourcing.publish-timeout", defaultValue = "PT5S")
  Duration publishTimeout;

  /** One attempt at delivering one event. Never throws. */
  public Delivery put(String eventId, EventEnvelope envelope) {
    URI target = URI.create(base() + EVENTS_PATH + eventId);
    try {
      HttpRequest request =
          HttpRequest.newBuilder(target)
              .timeout(publishTimeout)
              .header("Content-Type", "application/json")
              .PUT(
                  HttpRequest.BodyPublishers.ofString(
                      CanonicalJson.envelope(envelope), StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      return classify(eventId, response);
    } catch (InterruptedException e) {
      // Restore the flag and treat it as "not now": the caller is a worker being asked to stop, and
      // the event is worth exactly as much after the restart.
      Thread.currentThread().interrupt();
      return new Delivery(Outcome.RETRYABLE, "interrupted");
    } catch (Exception e) {
      LOG.debugf("PUT %s failed: %s", target, e.toString());
      return new Delivery(Outcome.RETRYABLE, e.toString());
    }
  }

  private Delivery classify(String eventId, HttpResponse<String> response) {
    int status = response.statusCode();
    if (status == 200 || status == 201) {
      LOG.debugf("event %s delivered (%d)", eventId, status);
      return new Delivery(Outcome.DELIVERED, null);
    }
    String detail = status + " " + firstLine(response.body());
    if (status == 400) {
      // The id exists with different content. The caller reused a UUID, which is a bug in the
      // publisher and not a condition of the network.
      LOG.warnf("event %s rejected as a reused id: %s", eventId, detail);
      return new Delivery(Outcome.REJECTED, detail);
    }
    return new Delivery(Outcome.RETRYABLE, detail);
  }

  /** {@code qits.events.url} is specified without a path; a trailing slash is tolerated rather than doubled. */
  private String base() {
    String url = eventsUrl.trim();
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String firstLine(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String line = body.strip().lines().findFirst().orElse("");
    return line.length() > 200 ? line.substring(0, 200) : line;
  }
}
