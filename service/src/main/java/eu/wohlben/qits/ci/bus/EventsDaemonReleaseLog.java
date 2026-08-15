package eu.wohlben.qits.ci.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiArtifact;
import eu.wohlben.qits.ci.control.CiDaemonPins;
import eu.wohlben.qits.ci.control.DaemonReleaseLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production {@link DaemonReleaseLog}: {@code GET
 * {qits.events.url}/events/api/events?name=SoftwareRelease&attr=packageType=daemon&attr=packageName=
 * qits-ci-daemon&limit=<n>} against the {@code ?attr=} filter workstream BU added -- exact,
 * repeatable, ANDed (ci-daemon-autoadopt-plan.md §1.6). Read the four fields {@link
 * CiDaemonPins#adopt} needs off the response: {@code id} and {@code occurredAt} from the envelope,
 * {@code version} out of the payload string.
 *
 * <p><b>Never throws.</b> A malformed response, a non-200, an unreachable qits-events -- every one
 * of them is a WARN and an empty list, because {@link DaemonReleaseLog#recentReleases}'s own
 * contract is that an unreachable log must leave the ladder exactly as it was, never empty it. An
 * empty return from here at startup means reconciliation adopts nothing this boot; the durable
 * table (and any live event that does arrive) is what the ladder answers from regardless.
 *
 * <p>An <b>instance</b> {@code HttpClient}, never a static one -- the same native-image constraint
 * {@code githost/HttpGitHostRepoListing} documents: a static client is built at image-build time and
 * native-image refuses the heap it lands in.
 */
@ApplicationScoped
public class EventsDaemonReleaseLog implements DaemonReleaseLog {

  private static final Logger LOG = Logger.getLogger(EventsDaemonReleaseLog.class);

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  @ConfigProperty(name = "qits.events.url")
  String eventsUrl;

  @Inject ObjectMapper objectMapper;

  @Override
  public List<Release> recentReleases(int limit) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(listUrl(limit)))
              .timeout(Duration.ofSeconds(10))
              .header("X-Qits-User", "qits-ci")
              .header("X-Qits-Roles", "qits:system")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warnf(
            "Could not read recent %s releases from qits-events: HTTP %d",
            CiDaemonPins.DAEMON_NAME, response.statusCode());
        return List.of();
      }
      return parse(response.body());
    } catch (Exception e) {
      LOG.warnf(
          "Could not read recent %s releases from qits-events: %s",
          CiDaemonPins.DAEMON_NAME, e.toString());
      return List.of();
    }
  }

  private String listUrl(int limit) {
    String encodedName = URLEncoder.encode("SoftwareRelease", StandardCharsets.UTF_8);
    String packageTypeAttr =
        URLEncoder.encode(
            "packageType=" + CiArtifact.Type.DAEMON.declared(), StandardCharsets.UTF_8);
    String packageNameAttr =
        URLEncoder.encode("packageName=" + CiDaemonPins.DAEMON_NAME, StandardCharsets.UTF_8);
    return eventsUrl.replaceAll("/+$", "")
        + "/events/api/events?name="
        + encodedName
        + "&attr="
        + packageTypeAttr
        + "&attr="
        + packageNameAttr
        + "&limit="
        + Math.max(1, limit);
  }

  /**
   * Every field this reads back is data qits-events itself already validated on the way in
   * ({@code occurredAt} mandatory, {@code payload} canonical JSON) -- one malformed row is skipped
   * with a WARN rather than losing the whole page, since a page otherwise holding a good row is
   * still worth reconciling from.
   */
  List<Release> parse(String body) {
    List<Release> releases = new ArrayList<>();
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (Exception e) {
      LOG.warnf("Could not parse qits-events' event list: %s", e.toString());
      return List.of();
    }
    for (JsonNode event : root.path("events")) {
      try {
        String id = event.path("id").asText(null);
        Instant occurredAt = Instant.parse(event.path("occurredAt").asText());
        JsonNode payload = objectMapper.readTree(event.path("payload").asText());
        String version = payload.path("version").asText(null);
        if (id == null || id.isBlank() || version == null || version.isBlank()) {
          LOG.warnf("Skipping a %s SoftwareRelease row with no id or version", CiDaemonPins.DAEMON_NAME);
          continue;
        }
        releases.add(new Release(version, id, occurredAt));
      } catch (Exception e) {
        LOG.warnf("Skipping an unreadable SoftwareRelease row: %s", e.toString());
      }
    }
    return List.copyOf(releases);
  }
}
