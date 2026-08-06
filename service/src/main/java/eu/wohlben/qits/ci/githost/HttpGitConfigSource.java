package eu.wohlben.qits.ci.githost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiConfigParser;
import eu.wohlben.qits.ci.control.CiConfigSource;
import eu.wohlben.qits.ci.control.CiEventTriggerParser;
import eu.wohlben.qits.ci.control.CiIdentifiers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production {@link CiConfigSource}: it reads a repository's pipeline config straight off
 * the git host's content endpoints.
 *
 * <pre>
 *   GET {qits.ci.git-host-url}/git/&lt;repoId&gt;/blob/&lt;rev&gt;/&lt;path&gt;  → the raw bytes
 *   GET {qits.ci.git-host-url}/git/&lt;repoId&gt;/tree/&lt;rev&gt;[/&lt;path&gt;] → {"entries":[{"name","type"}]}
 * </pre>
 *
 * <p>Both answer the commit they resolved in a {@code Git-Commit-Sha} header, and {@code rev} is a
 * full sha or a ref name — which is what lets the push path read <b>at the pushed sha</b> instead of
 * fetching a branch and hoping the commit is still on it. That is the whole of why this class no
 * longer keeps a bare mirror per repository: the wire protocol has no blob-at-path verb, so reading
 * one file used to mean cloning the repository first, with a local ref two workers could race for.
 * The mirror, the {@code git} shell-outs and the contended-fetch retry are gone with the reason for
 * them.
 *
 * <p>It lives in {@code service} rather than beside the port in {@code ci} for the reason every
 * client here does: {@code ci} stays free of {@code java.net.http} and of another service's wire
 * shape. Same arrangement as {@code HttpGitHostRepoListing}, one segment over.
 *
 * <h2>What a status means</h2>
 *
 * <ul>
 *   <li><b>200</b> — the bytes. Past {@link #MAX_CONFIG_BYTES} the file cannot be a config and is
 *       {@link ConfigLookup#invalid} rather than parsed as a head.
 *   <li><b>404 on the blob</b> — <em>this commit carries no config</em>, which is the opt-in case
 *       and by far the common one. Told apart from a commit the repository does not hold at all by
 *       one more read: a tree listing at the same sha, which 404s only when the rev itself does not
 *       resolve ⇒ {@link ConfigLookup#gone()}.
 *   <li><b>anything else</b> — the host could not answer, so nothing is recorded. A read failure
 *       must not invent a gate.
 * </ul>
 *
 * <p>Note what {@code GONE} now means, because it narrowed: the repository does not hold the commit
 * at all. It used to mean "no longer an ancestor of the branch tip", which discarded a run for a
 * commit that had merely been force-pushed past. Reading at the sha is what makes that distinction
 * unnecessary — a commit the host still holds is a commit whose pipeline can still be read and run.
 *
 * <h2>The timeouts</h2>
 *
 * <p>Short and bounded, because both callers are single-threaded workers: {@code ci-run-worker} for
 * a push and {@code ci-trigger-worker} for an arriving event, where every candidate repository costs
 * one of these calls. {@link #CONNECT_TIMEOUT} 2s and {@link #REQUEST_TIMEOUT} 5s, so a git host
 * that has stopped answering costs seconds per repository rather than a step's whole timeout. Same
 * 2s connect bound {@code HttpGitHostRepoListing} and {@code PdBuildNotifier} carry.
 *
 * <p>Every identifier is validated by {@link CiIdentifiers} before it reaches a URL, because the
 * intake that supplies them is reachable without a session. A branch may legitimately contain
 * {@code /}, and a rev is one path segment, so it is percent-encoded on the way in.
 *
 * <p>An <b>instance</b> {@code HttpClient}, never a static one — a static client is built at
 * image-build time and native-image refuses the heap it lands in.
 */
@ApplicationScoped
public class HttpGitConfigSource implements CiConfigSource {

  private static final Logger LOG = Logger.getLogger(HttpGitConfigSource.class);

  /** Bound on opening the socket — see the class javadoc. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

  /** Bound on the whole exchange, response body included. */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

  /** The header both content routes answer the resolved commit in. */
  static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  /** A config file larger than this is not a config file; refuse it rather than parse a head. */
  static final int MAX_CONFIG_BYTES = 1024 * 1024;

  /**
   * How many {@code ci-event-*.yml} files one repository may declare. This listing runs per
   * repository per arriving event, so the count is work per frame and it belongs to qits-ci to bound
   * rather than to the repository to choose.
   */
  static final int MAX_TRIGGER_FILES = 32;

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  @ConfigProperty(name = "qits.ci.git-host-url")
  String gitHostUrl;

  @Inject ObjectMapper objectMapper;

  /** One answered request: the status, the body, and the commit the host resolved. */
  private record Answer(int status, byte[] body, String commitSha) {

    static final Answer FAILED = new Answer(-1, new byte[0], null);

    boolean ok() {
      return status == 200;
    }

    boolean notFound() {
      return status == 404;
    }

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  /**
   * The push half: {@link CiConfigParser#CONFIG_PATH} at the pushed commit itself.
   *
   * <p>The sha is read directly, so nothing here depends on where the branch is now — a second push
   * landing between the intake and this read changes nothing about what this commit declared. The
   * branch is still validated and still travels, because it is the run's own coordinate.
   */
  @Override
  public ConfigLookup read(String repoId, String branch, String sha) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);
    CiIdentifiers.requireSha(sha);

    String url = blobUrl(repoId, sha, CiConfigParser.CONFIG_PATH);
    Answer blob = get(url);
    if (blob.ok()) {
      if (blob.body().length > MAX_CONFIG_BYTES) {
        return ConfigLookup.invalid(
            CiConfigParser.CONFIG_PATH + " is larger than " + MAX_CONFIG_BYTES + " bytes");
      }
      return ConfigLookup.found(blob.text());
    }
    if (!blob.notFound()) {
      LOG.warnf("ci could not read %s: HTTP %d", url, blob.status());
      return ConfigLookup.unreachable();
    }
    // 404 on the blob is either "this commit declares no pipeline" or "this repository does not
    // hold this commit", and the two mean opposite things to the record. One tree listing at the
    // same sha tells them apart: it 404s only when the rev does not resolve.
    Answer commit = get(treeUrl(repoId, sha, ""));
    if (commit.ok()) {
      return ConfigLookup.absent();
    }
    if (commit.notFound()) {
      return ConfigLookup.gone();
    }
    return ConfigLookup.unreachable();
  }

  /**
   * The event half: every {@code .config/qits/ci-event-*.yml} at the branch's current head, with the
   * head it read them at.
   *
   * <p><b>The listing resolves the head and the reads are pinned to it.</b> The directory is listed
   * at the branch, the host answers which commit that was, and every file is then read at <em>that
   * sha</em> — so a push landing mid-evaluation cannot leave a run recorded against one commit with
   * a trigger file from another.
   *
   * <p>A repository with no {@code .config/qits/} at all is the ordinary case and not a failure, but
   * a 404 on the directory alone cannot say whether the branch exists. So it costs one more read —
   * the root tree at the branch — and only that one decides between "declares nothing" and "could
   * not be asked".
   *
   * <p><b>Two bounds, both because this walks another repository's tree.</b> A name that is not a
   * plain slug is not a trigger file ({@link CiEventTriggerParser#isTriggerPath}) — it comes back
   * from the host and goes straight into a URL — and a repository may declare at most {@link
   * #MAX_TRIGGER_FILES} of them, because this runs per repository per arriving event and an
   * unbounded count is an unbounded amount of work per frame.
   */
  @Override
  public EventTriggerLookup readEventTriggers(String repoId, String branch) {
    CiIdentifiers.requireRepoId(repoId);
    CiIdentifiers.requireBranch(branch);

    String dir = CiEventTriggerParser.CONFIG_DIR.replaceAll("/+$", "");
    Answer listed = get(treeUrl(repoId, branch, dir));
    if (!listed.ok()) {
      return noTriggerDirectory(repoId, branch, listed);
    }
    String headSha = listed.commitSha();
    if (headSha == null || headSha.isBlank()) {
      LOG.debugf("The git host answered no %s for %s@%s", COMMIT_SHA_HEADER, repoId, branch);
      return EventTriggerLookup.unreachable();
    }

    List<EventTriggerFile> files = new ArrayList<>();
    for (String name : entryNames(repoId, listed)) {
      String path = CiEventTriggerParser.CONFIG_DIR + name;
      if (!CiEventTriggerParser.isTriggerPath(path)) {
        continue;
      }
      if (files.size() >= MAX_TRIGGER_FILES) {
        LOG.warnf(
            "%s declares more than %d event triggers — reading the first %d",
            repoId, MAX_TRIGGER_FILES, MAX_TRIGGER_FILES);
        break;
      }
      Answer file = get(blobUrl(repoId, headSha, path));
      if (!file.ok()) {
        LOG.warnf("ci could not read %s at %s in %s", path, headSha, repoId);
        continue;
      }
      if (file.body().length > MAX_CONFIG_BYTES) {
        // Loud rather than parsed: half a selection is a different selection.
        LOG.warnf(
            "%s in %s is larger than %d bytes — not read as a trigger",
            path, repoId, MAX_CONFIG_BYTES);
        continue;
      }
      files.add(new EventTriggerFile(path, file.text()));
    }
    return EventTriggerLookup.found(headSha, files);
  }

  /**
   * What a failed listing of {@code .config/qits/} means, which the branch's root tree decides: the
   * repository is there and declares nothing, or it could not be asked at all.
   *
   * <p>Both are DEBUG, never WARN, and that is deliberate. This path asks <b>every</b> repository
   * qits-ci has heard of on <b>every</b> arriving event, so a repository that has since been deleted
   * or renamed would otherwise cost one warning per green build, platform-wide, forever. A warning
   * that cannot be acted on and never stops is how a log stops being read.
   */
  private EventTriggerLookup noTriggerDirectory(String repoId, String branch, Answer listed) {
    if (!listed.notFound()) {
      LOG.debugf("ci could not list %s in %s: HTTP %d", branch, repoId, listed.status());
      return EventTriggerLookup.unreachable();
    }
    Answer root = get(treeUrl(repoId, branch, ""));
    if (root.ok() && root.commitSha() != null && !root.commitSha().isBlank()) {
      return EventTriggerLookup.found(root.commitSha(), List.of());
    }
    LOG.debugf("ci could not resolve %s in %s: HTTP %d", branch, repoId, root.status());
    return EventTriggerLookup.unreachable();
  }

  /** The {@code name}s of a tree listing's entries, or none when the body is not that shape. */
  private List<String> entryNames(String repoId, Answer listed) {
    try {
      JsonNode root = objectMapper.readTree(listed.body());
      JsonNode entries = root == null ? null : root.get("entries");
      if (entries == null || !entries.isArray()) {
        LOG.warnf("The git host's tree listing for %s carries no \"entries\" array", repoId);
        return List.of();
      }
      List<String> names = new ArrayList<>();
      for (JsonNode entry : entries) {
        JsonNode name = entry.get("name");
        if (name != null && name.isTextual()) {
          names.add(name.asText());
        }
      }
      return names;
    } catch (Exception notJson) {
      LOG.warnf("The git host's tree listing for %s is not JSON: %s", repoId, notJson.toString());
      return List.of();
    }
  }

  /** One GET. Never throws: a transport failure is {@link Answer#FAILED}, which is not a 404. */
  private Answer get(String url) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      return new Answer(
          response.statusCode(),
          response.body() == null ? new byte[0] : response.body(),
          response.headers().firstValue(COMMIT_SHA_HEADER).orElse(null));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.debugf("Interrupted reading %s from the git host", url);
      return Answer.FAILED;
    } catch (Exception e) {
      LOG.debugf("Could not read %s from the git host: %s", url, e.toString());
      return Answer.FAILED;
    }
  }

  /** {@code <base>/git/<repoId>} — the same base the repository listing and a clone address. */
  private String repoUrl(String repoId) {
    return gitHostUrl.replaceAll("/+$", "") + "/git/" + repoId;
  }

  String blobUrl(String repoId, String rev, String path) {
    return repoUrl(repoId) + "/blob/" + encodeRev(rev) + "/" + path;
  }

  String treeUrl(String repoId, String rev, String path) {
    return repoUrl(repoId) + "/tree/" + encodeRev(rev) + (path.isEmpty() ? "" : "/" + path);
  }

  /**
   * A rev is one path segment, so a slashy branch is percent-encoded. Nothing else needs escaping:
   * {@link CiIdentifiers#requireBranch} accepts only {@code [A-Za-z0-9._/-]}, and a sha is hex.
   */
  private static String encodeRev(String rev) {
    return rev.replace("/", "%2F");
  }
}
