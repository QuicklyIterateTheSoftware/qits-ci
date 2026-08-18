package eu.wohlben.qits.ci.githost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * qits-githost, as much of it as qits-ci reads: the two content routes on a real socket, answered
 * out of ordinary bare repositories a test seeds on disk.
 *
 * <pre>
 *   GET /git/&lt;repoId&gt;/blob/&lt;rev&gt;/&lt;path&gt;   → the bytes, plus Git-Commit-Sha
 *   GET /git/&lt;repoId&gt;/tree/&lt;rev&gt;[/&lt;path&gt;]  → {"entries":[{"name","type"}]}
 *   GET /git                                → {"repositories":[…]}
 * </pre>
 *
 * <p><b>The base moved out of {@code /artifacts} and that is the point of it being spelled here.</b>
 * The git host used to be part of qits-artifacts and borrow that service's gateway segment; standing
 * alone it serves {@code /git/**} at the root. So {@code qits.ci.git-host-url} is scheme+host+port
 * with no path, and this stub is what proves qits-ci builds its urls from such a base correctly —
 * a stub left on the old prefix would keep the suite green about a shape nothing serves.
 *
 * <p>It shells {@code git} against {@code <root>/git/<repoId>}, which is what the real host does
 * through JGit — so the suites keep seeding bares with the git they already use and this stub owns
 * nothing but the wire shape. That shape is the contract, spelled out here rather than imported: a
 * stub standing in for another <em>service</em> is written against what that service published, and
 * if the two ever disagree the read fails here exactly as it would in a deployment.
 *
 * <p>It replaces the {@code file://} directory the suites used to point {@code qits.ci.git-host-url}
 * at. That stand-in worked while ci cloned the repository to read one file; content reads are HTTP
 * and a directory answers none of them.
 *
 * <p>As a {@link QuarkusTestResourceLifecycleManager} it hands the port to Quarkus <b>before it
 * boots</b>, which is the only way an ephemeral port reaches the application's config — the same
 * arrangement, and the same reason, as {@code bus/StubEventsServer}. Declared at {@code
 * TestResourceScope.GLOBAL}, so the server is one per test run and no suite restarts for it. The ITs drive
 * {@link #start} directly instead, because they own their own root directory and their own profile.
 */
public class StubGitHost implements QuarkusTestResourceLifecycleManager {

  /** qits-githost's own segment, a literal in its {@code GitHostRoutes} exactly as it is here. */
  public static final String BASE = "/git";

  /** The header both content routes answer the resolved commit in. */
  public static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  /** Where the @QuarkusTest suites seed their bares — {@code <root>/git/<repoId>}. */
  public static final Path ROOT =
      Path.of(System.getProperty("user.dir"), "target", "ci-svc-test-git-host");

  private static Server shared;

  /** One running stub: the port it took, the base a caller configures, and what it last read. */
  public record Server(HttpServer http, int port, AtomicReference<String> seenAuthorization) {

    public String gitHostUrl() {
      return "http://127.0.0.1:" + port;
    }

    /**
     * The {@code Authorization} header of the last request, or null when it carried none.
     *
     * <p>The real host guards its content routes on a bearer, and this stub answers everyone — so
     * without this the suite could not tell a read that carries the credential from one that has
     * quietly stopped carrying it.
     */
    public String lastAuthorization() {
      return seenAuthorization.get();
    }

    public void stop() {
      http.stop(0);
    }
  }

  /** Starts a stub serving the bares under {@code <root>/git/}, on a free port. */
  public static Server start(Path root) throws Exception {
    Files.createDirectories(root.resolve("git"));
    AtomicReference<String> seen = new AtomicReference<>();
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        BASE,
        exchange -> {
          seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
          answer(root, exchange);
        });
    http.start();
    return new Server(http, http.getAddress().getPort(), seen);
  }

  @Override
  public Map<String, String> start() {
    try {
      deleteRecursively(ROOT);
      shared = start(ROOT);
      return Map.of("qits.ci.git-host-url", shared.gitHostUrl());
    } catch (Exception e) {
      throw new IllegalStateException("could not start the stub git host", e);
    }
  }

  @Override
  public void stop() {
    if (shared != null) {
      shared.stop();
      shared = null;
    }
  }

  private static void answer(Path root, HttpExchange exchange) {
    try (exchange) {
      // The RAW path: a slashy branch arrives percent-encoded and only the rev segment is decoded.
      String path = exchange.getRequestURI().getRawPath().substring(BASE.length());
      if (path.isEmpty() || path.equals("/")) {
        send(exchange, 200, json("repositories", repositories(root)), null);
        return;
      }
      String[] segments = path.substring(1).split("/", 4);
      if (segments.length < 3) {
        send(exchange, 400, "not a content read".getBytes(StandardCharsets.UTF_8), null);
        return;
      }
      Path bare = root.resolve("git").resolve(segments[0]);
      String kind = segments[1];
      String rev = URLDecoder.decode(segments[2], StandardCharsets.UTF_8);
      String file = segments.length == 4 ? segments[3] : "";
      if (!Files.isDirectory(bare)) {
        send(exchange, 404, new byte[0], null);
        return;
      }
      String sha = git(bare, "rev-parse", "--verify", rev + "^{commit}");
      if (sha == null) {
        send(exchange, 404, new byte[0], null);
        return;
      }
      sha = sha.strip();
      byte[] body =
          switch (kind) {
            case "blob" -> file.isEmpty() ? null : bytes(bare, "cat-file", "blob", sha + ":" + file);
            case "tree" -> tree(bare, sha, file);
            default -> null;
          };
      send(exchange, body == null ? 404 : 200, body == null ? new byte[0] : body, sha);
    } catch (Exception e) {
      throw new IllegalStateException("the stub git host could not answer", e);
    }
  }

  /** {@code {"entries":[{"name","type"}]}} for the directory at {@code sha:path}, or null. */
  private static byte[] tree(Path bare, String sha, String path) throws Exception {
    String listed = git(bare, "ls-tree", sha + ":" + path);
    if (listed == null) {
      return null;
    }
    StringBuilder entries = new StringBuilder("{\"entries\":[");
    boolean first = true;
    for (String line : listed.split("\\R")) {
      if (line.isBlank()) {
        continue;
      }
      // <mode> <type> <sha>\t<name>; a gitlink is `commit`, which a caller can only read as a blob.
      String[] head = line.split("\\s+", 3);
      String type = head[1].equals("tree") ? "tree" : "blob";
      String name = line.substring(line.indexOf('\t') + 1);
      entries
          .append(first ? "" : ",")
          .append("{\"name\":\"")
          .append(name)
          .append("\",\"type\":\"")
          .append(type)
          .append("\"}");
      first = false;
    }
    return entries.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Every bare directly under {@code <root>/git/}. */
  private static List<String> repositories(Path root) throws Exception {
    Path git = root.resolve("git");
    if (!Files.isDirectory(git)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(git)) {
      return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  private static byte[] json(String key, List<String> values) {
    StringBuilder body = new StringBuilder("{\"").append(key).append("\":[");
    for (int i = 0; i < values.size(); i++) {
      body.append(i == 0 ? "" : ",").append('"').append(values.get(i)).append('"');
    }
    return body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
  }

  private static void send(HttpExchange exchange, int status, byte[] body, String sha)
      throws Exception {
    if (sha != null) {
      exchange.getResponseHeaders().add(COMMIT_SHA_HEADER, sha);
    }
    exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }

  /** {@code git} in the bare, as text, or null when it failed — which is this stub's 404. */
  private static String git(Path bare, String... args) throws Exception {
    byte[] out = bytes(bare, args);
    return out == null ? null : new String(out, StandardCharsets.UTF_8);
  }

  /** The same, as bytes: a blob is content and must not go through a decode. */
  private static byte[] bytes(Path bare, String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-C", bare.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    process.getInputStream().transferTo(captured);
    process.getErrorStream().readAllBytes();
    return process.waitFor() == 0 ? captured.toByteArray() : null;
  }

  static void deleteRecursively(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }
  }
}
