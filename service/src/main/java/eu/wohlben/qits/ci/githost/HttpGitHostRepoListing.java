package eu.wohlben.qits.ci.githost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import eu.wohlben.qits.ci.control.GitHostRepoListing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production {@link GitHostRepoListing}: {@code GET {qits.ci.git-host-url}/git} → {@code
 * {"repositories":["<repoId>", …]}}.
 *
 * <p>The url is the git host's own base with the same {@code /git} segment ci already appends to
 * fetch a ref, so the listing needs no configuration of its own — it is the directory above the
 * bares qits-ci clones from, and moving one moves both.
 *
 * <p><b>Never throws.</b> An unreachable host, a non-200, a body that is not JSON and a body without
 * a {@code repositories} array are each one WARN naming the url and an empty set, because {@link
 * GitHostRepoListing#repositories()}'s contract is that a read failure leaves the candidate set as
 * the known one rather than emptying it.
 *
 * <h2>The timeouts, and why they are this short</h2>
 *
 * <p>This call sits on {@code ci-trigger-worker} in front of every evaluation, and that thread is
 * single-threaded by design — an untimed call here would stall every arriving event, not just this
 * one. The evaluation it precedes then does a {@code git fetch} per candidate, so the listing is the
 * cheapest part of the work and must never be the slowest: {@link #CONNECT_TIMEOUT} 2s and {@link
 * #REQUEST_TIMEOUT} 3s, after which the known set is a correct answer and waiting longer buys
 * nothing. Same 2s connect bound {@code CdBuildNotifier} and {@code EventsDaemonReleaseLog} carry.
 *
 * <h2>The cache</h2>
 *
 * <p>{@link #CACHE_TTL} is five seconds, and it is deliberately trivial: one evaluation already does
 * a git fetch per candidate, so the listing is never the cost worth optimising — the cache exists so
 * a burst of events on the bus is one listing read rather than one per frame. Only a <b>successful</b>
 * read is cached; a failure is retried by the next event, which is what keeps a git host that came
 * back up from staying invisible for a window.
 *
 * <p>An <b>instance</b> {@code HttpClient}, never a static one — the same native-image constraint
 * {@code CdBuildNotifier} documents: a static client is built at image-build time and native-image
 * refuses the heap it lands in. Reading the body is {@code readTree} and a walk, so nothing here
 * needs reflection registering either.
 */
@ApplicationScoped
public class HttpGitHostRepoListing implements GitHostRepoListing {

  private static final Logger LOG = Logger.getLogger(HttpGitHostRepoListing.class);

  /** Bound on opening the socket — see the class javadoc. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on the whole exchange, response body included. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

  /** How long one successful listing stands in for the next read. */
  static final Duration CACHE_TTL = Duration.ofSeconds(5);

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.ci.git-host-url")
  String gitHostUrl;

  @Inject ObjectMapper objectMapper;

  /** One successful read and when it stops standing in. Volatile, not locked: a duplicate read
   *  under a race costs one HTTP call, and this whole class is called from one thread anyway. */
  private volatile Cached cached;

  private record Cached(Set<String> ids, long expiresAt) {}

  @Override
  public Set<String> repositories() {
    Cached fresh = cached;
    if (fresh != null && System.nanoTime() < fresh.expiresAt()) {
      return fresh.ids();
    }
    String url = listingUrl();
    if (!isHttp(url)) {
      // A git host addressed over file:// serves no listing and never could. DEBUG rather than
      // WARN: this is the suites' own stand-in, and a warning per event forever is how a log stops
      // being read (GitConfigFetcher#fetchBranch makes the same argument at length).
      LOG.debugf("%s is not an HTTP git host — no repository listing to read", url);
      return Set.of();
    }
    Set<String> ids = read(url);
    if (ids == null) {
      return Set.of();
    }
    cached = new Cached(ids, System.nanoTime() + CACHE_TTL.toNanos());
    return ids;
  }

  /** The listing, or null when it could not be read — the one caller turns null into empty. */
  private Set<String> read(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        LOG.warnf(
            "Could not read the git host's repository listing from %s: HTTP %d — evaluating only"
                + " the repositories qits-ci already knows",
            url, response.statusCode());
        return null;
      }
      return parse(url, response.body());
    } catch (Exception e) {
      LOG.warnf(
          "Could not read the git host's repository listing from %s: %s — evaluating only the"
              + " repositories qits-ci already knows",
          url, e.toString());
      return null;
    }
  }

  /**
   * {@code {"repositories":[…]}} into ids, or null when the body is not that. A single id that is
   * not a repo id is skipped rather than failing the page — it reaches a {@code git} argv, and the
   * rest of a listing is still worth evaluating.
   */
  Set<String> parse(String url, String body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (Exception notJson) {
      LOG.warnf(
          "The git host's repository listing at %s is not JSON: %s — evaluating only the"
              + " repositories qits-ci already knows",
          url, notJson.toString());
      return null;
    }
    JsonNode repositories = root == null ? null : root.get("repositories");
    if (repositories == null || !repositories.isArray()) {
      LOG.warnf(
          "The git host's repository listing at %s carries no \"repositories\" array — evaluating"
              + " only the repositories qits-ci already knows",
          url);
      return null;
    }
    Set<String> ids = new TreeSet<>();
    for (JsonNode entry : repositories) {
      String id = entry.isTextual() ? entry.asText() : null;
      if (id == null || !isRepoId(id)) {
        LOG.debugf("Skipping %s from the git host's listing at %s — not a repository id", id, url);
        continue;
      }
      ids.add(id);
    }
    return ids;
  }

  /** {@code <base>/git} — the directory above the bares {@code GitConfigFetcher} fetches from. */
  String listingUrl() {
    return gitHostUrl.replaceAll("/+$", "") + "/git";
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

  private static boolean isRepoId(String id) {
    try {
      CiIdentifiers.requireRepoId(id);
      return true;
    } catch (RuntimeException notAnId) {
      return false;
    }
  }
}
