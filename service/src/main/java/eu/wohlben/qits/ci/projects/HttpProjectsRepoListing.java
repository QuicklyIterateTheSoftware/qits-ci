package eu.wohlben.qits.ci.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRepositoryListing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production {@link CiRepositoryListing}: {@code GET {qits.ci.projects-url}
 * /projects/api/repositories} → {@code {"repositories":[{"id","projectId","name","mainBranch"}, …]}}.
 *
 * <p>It is the enumeration the trigger engine prefers, and it exists because the git host's own
 * {@code GET /git} stopped being usable for it: after the identity cutover that route is an internal
 * storage listing answering opaque UUIDs, gated to qits-projects' own client. qits-projects is where
 * the public {@code (projectId, name)} coordinate lives, so that is where the candidate list comes
 * from — and a candidate without a name is a candidate no trigger file could ever select and no
 * clone URL could be built for.
 *
 * <p><b>The url is configured, unlike the git host's, and the key is a kill switch.</b> {@code
 * qits.ci.projects-url} has no default: unset means this listing is not {@link #configured()}, and
 * {@code ListedAndKnownCiRepos} asks the git host instead — which is what keeps a pre-cutover
 * platform triggering and a clone-alone build of this repository running with no qits-projects
 * anywhere. It could not be derived the way the git host's listing url is: this is a different
 * service at a different address, not a second path under one qits-ci already holds.
 *
 * <p><b>Never throws.</b> An unreachable service, a non-200 (the contract says a failed read is a
 * 5xx), a body that is not JSON and a body without a {@code repositories} array are each one WARN
 * naming the url and an empty list, because {@link CiRepositoryListing#repositories()}'s contract is
 * that a read failure leaves the candidate set as the known one rather than emptying it.
 *
 * <h2>What it skips, and why that is not shrinking the set</h2>
 *
 * <p>An entry with a null or blank {@code name} is dropped. It has no public address, so there is no
 * content route this engine could read its trigger files from — answering it id-addressed would be a
 * read against a route that is qits-projects' alone. An entry whose id, project or name is not
 * something {@link CiIdentifiers} accepts is dropped for the reason the git host's listing drops
 * one: all three reach a URL, and the rest of a listing is still worth evaluating.
 *
 * <h2>Authentication</h2>
 *
 * <p>It presents {@code X-Qits-User}/{@code X-Qits-Roles: qits:system} — the same forward-auth
 * identity {@code githost/HttpGitHostRepoListing} presents for the same kind of in-network read, and
 * exactly the role the endpoint's contract asks for. No bearer: qits-ci's two OIDC clients are
 * audience-bound to qits-containers and qits-githost, and presenting either here would be a token
 * addressed to another service — worse than none, since a request carrying no {@code Authorization}
 * falls through to the header mechanism while one carrying a wrong-audience bearer is refused. An
 * audience-bound client for this hop is a deployment-config change, and it belongs in the campaign's
 * wiring work rather than here.
 *
 * <h2>The timeouts and the cache</h2>
 *
 * <p>Both are the git-host listing's, for its reasons: this call sits on the single-threaded {@code
 * ci-trigger-worker} in front of every evaluation, so an untimed one would stall every arriving
 * event ({@link #CONNECT_TIMEOUT} 2s, {@link #REQUEST_TIMEOUT} 3s), and {@link #CACHE_TTL} five
 * seconds is so a burst of frames is one listing read rather than one per frame. Only a
 * <b>successful</b> read is cached, so a service that came back up is not invisible for a window.
 *
 * <p><b>An instance {@code HttpClient}, never a static one</b> — a static client is created at
 * image-build time and native-image refuses the heap it lands in. Reading the body is {@code
 * readTree} and a walk, so nothing here needs reflection registering either.
 */
@ApplicationScoped
public class HttpProjectsRepoListing implements CiRepositoryListing {

  private static final Logger LOG = Logger.getLogger(HttpProjectsRepoListing.class);

  /** Bound on opening the socket — see the class javadoc. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on the whole exchange, response body included. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  /** How long one successful listing stands in for the next read. */
  static final Duration CACHE_TTL = Duration.ofSeconds(5);

  /** The path qits-projects serves its repository catalogue under. */
  static final String LISTING_PATH = "/projects/api/repositories";

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  /**
   * Where qits-projects answers. <b>Optional and unset by default</b> — see the class javadoc: an
   * absent value is a deployment that has not moved to the projects catalogue yet, not a
   * misconfiguration.
   */
  @ConfigProperty(name = "qits.ci.projects-url")
  Optional<String> projectsUrl;

  @Inject ObjectMapper objectMapper;

  /**
   * One successful read and when it stops standing in. Volatile, not locked: a duplicate read under
   * a race costs one HTTP call, and this whole class is called from one thread anyway.
   */
  private volatile Cached cached;

  private record Cached(List<CiRepoRef> repositories, long expiresAt) {}

  @Override
  public boolean configured() {
    return projectsUrl.filter(url -> !url.isBlank()).isPresent();
  }

  @Override
  public List<CiRepoRef> repositories() {
    if (!configured()) {
      return List.of();
    }
    Cached fresh = cached;
    if (fresh != null && System.nanoTime() < fresh.expiresAt()) {
      return fresh.repositories();
    }
    String url = listingUrl();
    if (!isHttp(url)) {
      // A qits-projects addressed over anything but HTTP serves no listing and never could. DEBUG
      // rather than WARN, the argument HttpGitHostRepoListing makes: a warning per event forever is
      // how a log stops being read.
      LOG.debugf("%s is not an HTTP qits-projects — no repository listing to read", url);
      return List.of();
    }
    List<CiRepoRef> repositories = read(url);
    if (repositories == null) {
      return List.of();
    }
    cached = new Cached(repositories, System.nanoTime() + CACHE_TTL.toNanos());
    return repositories;
  }

  /** The listing, or null when it could not be read — the one caller turns null into empty. */
  private List<CiRepoRef> read(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(REQUEST_TIMEOUT)
              .header("X-Qits-User", "qits-ci")
              .header("X-Qits-Roles", "qits:system")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warnf(
            "Could not read the repository catalogue from %s: HTTP %d — evaluating only the"
                + " repositories qits-ci already knows",
            url, response.statusCode());
        return null;
      }
      return parse(url, response.body());
    } catch (Exception e) {
      LOG.warnf(
          "Could not read the repository catalogue from %s: %s — evaluating only the repositories"
              + " qits-ci already knows",
          url, e.toString());
      return null;
    }
  }

  /**
   * {@code {"repositories":[{"id","projectId","name",…}]}} into references, or null when the body is
   * not that. One unusable entry is skipped rather than failing the page: the rest of a listing is
   * still worth evaluating, which is the git-host listing's rule for the same reason.
   */
  List<CiRepoRef> parse(String url, String body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (Exception notJson) {
      LOG.warnf(
          "The repository catalogue at %s is not JSON: %s — evaluating only the repositories qits-ci"
              + " already knows",
          url, notJson.toString());
      return null;
    }
    JsonNode repositories = root == null ? null : root.get("repositories");
    if (repositories == null || !repositories.isArray()) {
      LOG.warnf(
          "The repository catalogue at %s carries no \"repositories\" array — evaluating only the"
              + " repositories qits-ci already knows",
          url);
      return null;
    }
    List<CiRepoRef> refs = new ArrayList<>();
    for (JsonNode entry : repositories) {
      CiRepoRef ref = refOf(entry);
      if (ref == null) {
        LOG.debugf("Skipping %s from the repository catalogue at %s — no usable address", entry, url);
        continue;
      }
      refs.add(ref);
    }
    return List.copyOf(refs);
  }

  /** One catalogue entry as a reference, or null when it names no address this engine can read. */
  private static CiRepoRef refOf(JsonNode entry) {
    CiRepoRef ref =
        CiRepoRef.of(text(entry, "id"), text(entry, "projectId"), text(entry, "name"));
    if (!ref.named()) {
      // No public address ⇒ no content route ⇒ no trigger files to read. Not a candidate.
      return null;
    }
    try {
      return CiIdentifiers.requireRepo(ref);
    } catch (RuntimeException notAddressable) {
      return null;
    }
  }

  private static String text(JsonNode entry, String field) {
    JsonNode at = entry == null ? null : entry.get(field);
    return at == null || !at.isTextual() ? null : at.textValue();
  }

  /** {@code <base>/projects/api/repositories}. */
  String listingUrl() {
    return projectsUrl.orElse("").replaceAll("/+$", "") + LISTING_PATH;
  }

  private static boolean isHttp(String url) {
    try {
      String scheme = URI.create(url).getScheme();
      return scheme != null
          && switch (scheme.toLowerCase(Locale.ROOT)) {
            case "http", "https" -> true;
            default -> false;
          };
    } catch (RuntimeException notAUrl) {
      return false;
    }
  }
}
