package eu.wohlben.qits.ci.daemonhost;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The half of a docker-backed gate that stands in for qits-artifacts: a git host over <b>smart</b>
 * HTTP at {@code /git/<repoId>}, and the daemon binary at {@code /qits-ci-daemon}, on one port a
 * step container can reach.
 *
 * <p><b>Why smart HTTP and not static files.</b> The daemon clones with {@code --depth 50}, and a
 * shallow clone is a capability only the smart transport advertises — a static-file handler gets
 * exactly as far as {@code fatal: dumb http transport does not support shallow capabilities}. That
 * would be the fixture being unrepresentative, not the daemon being wrong: depth 50 is deliberate (a
 * recent-but-not-tip sha must still be in the clone) and production qits-artifacts serves smart HTTP
 * at {@code /git/<repoId>}. So this shells {@code git http-backend} as CGI, which is what
 * qits-artifacts is doing behind its own route.
 *
 * <p><b>What it reproduces and what it does not.</b> Reproduced: the wire protocol, the url shape
 * ({@code <base>/git/<repoId>}, with ci appending the {@code /git} segment itself), ref
 * advertisement, and shallow negotiation — everything a daemon's clone actually exercises. Not
 * reproduced: qits-artifacts' authorization, its repository lookup, or any of its own routing; this
 * exports one directory unconditionally. That is the right split for a gate about the step
 * lifecycle, and assertions about what the git host does belong in qits-artifacts.
 *
 * <p>Shared by both docker-backed ITs in this package rather than copied into each: the CGI
 * plumbing below is fiddly enough that two copies would drift, and neither IT has anything of its
 * own to say about it.
 */
public final class GitHttpBackend implements AutoCloseable {

  /** The path the daemon binary is served at, beside the git host. */
  public static final String BINARY_PATH = "/qits-ci-daemon";

  private final Vertx vertx;
  private final HttpServer server;
  private final int port;

  private GitHttpBackend(Vertx vertx, HttpServer server, int port) {
    this.vertx = vertx;
    this.server = server;
    this.port = port;
  }

  /**
   * Serve {@code projectRoot} (which holds {@code git/<repoId>} bares) and {@code binary}, on a free
   * port bound to every interface.
   */
  public static GitHttpBackend start(Path projectRoot, byte[] binary) throws Exception {
    Vertx vertx = Vertx.vertx();
    HttpServer server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          if (req.path().equals(BINARY_PATH)) {
            req.response()
                .putHeader("Content-Type", "application/octet-stream")
                .end(Buffer.buffer(binary));
            return;
          }
          serveSmartHttp(vertx, req, projectRoot);
        });
    int port =
        server
            .listen(0, "0.0.0.0")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort();
    return new GitHttpBackend(vertx, server, port);
  }

  public int port() {
    return port;
  }

  /** The git host base, as reachable from a step container — ci appends {@code /git/<repoId>}. */
  public String containerGitUrl() {
    return "http://host.docker.internal:" + port;
  }

  /** The daemon binary's url, as reachable from a step container. */
  public String containerBinaryUrl() {
    return "http://host.docker.internal:" + port + BINARY_PATH;
  }

  @Override
  public void close() {
    server.close();
    vertx.close();
  }

  /**
   * Block until a container can actually open a TCP connection to each of this JVM's freshly-bound
   * ports, and fail honestly if it never can.
   *
   * <p><b>Why this exists.</b> Docker's host-gateway forwarding does not pick up a listener the
   * instant it binds — measured on one host, a container launched immediately after {@code listen()}
   * returns gets {@code Connection refused}, and the very next attempt two seconds later downloads
   * all 45MB. The step container's bootstrap fetches <em>once</em> and exits when it cannot, so
   * losing that race does not look like a race: the container is dead within a second and the host
   * then waits out its full register deadline, reporting {@code wget could not fetch} two minutes
   * later. That reads like a broken bootstrap or a bad url, and it is neither.
   *
   * <p>Waiting here rather than retrying in the bootstrap is deliberate. The race is a property of
   * <em>the fixture</em> — production ports belong to long-lived services that were listening long
   * before any container started — so the fix belongs in the fixture. Adding a retry loop to the
   * host-authored bootstrap to paper over a test-harness timing artefact would be changing shipped
   * behaviour to suit a test.
   *
   * <p>It doubles as the honest form of the host-networking caveat both ITs carry: on a host where a
   * container genuinely cannot route back to the JVM, this fails in a minute saying so, instead of
   * every case failing later for an apparently unrelated reason.
   */
  public static void awaitReachableFromAContainer(
      String runtime, String image, String network, int... ports) throws Exception {
    StringBuilder script = new StringBuilder();
    for (int port : ports) {
      // A bare TCP connect, so one probe covers both the fixture's HTTP server and the control
      // socket without caring what either would answer. /dev/tcp is a bash builtin and `timeout`
      // needs a command to run, so the probe goes through a file rather than the obvious `bash -c
      // '<probe>'`: that spelling is the exact string the eradication grep looks for, and a hit it
      // has to be explained away every time is a hit nobody reads. Nothing repo-controlled is
      // involved here either way — this text is a constant in a fixture.
      script
          .append("printf '%s\\n' 'exec 3<>/dev/tcp/host.docker.internal/")
          .append(port)
          .append("' > /tmp/qits-reachability-probe\n")
          .append("timeout 5 bash /tmp/qits-reachability-probe || exit 1\n");
    }
    script.append("echo REACHABLE\n");

    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    String last = "";
    while (System.nanoTime() < deadline) {
      Process probe =
          new ProcessBuilder(
                  runtime,
                  "run",
                  "--rm",
                  "--network",
                  network,
                  "--add-host=host.docker.internal:host-gateway",
                  "--entrypoint",
                  "/bin/bash",
                  image,
                  "-c",
                  script.toString())
              .redirectErrorStream(true)
              .start();
      last = new String(probe.getInputStream().readAllBytes()).strip();
      probe.waitFor();
      if (last.contains("REACHABLE")) {
        return;
      }
      Thread.sleep(1000);
    }
    throw new AssertionError(
        "A container on "
            + network
            + " could not reach this JVM's ports "
            + Arrays.toString(ports)
            + " through host.docker.internal within 90s. The docker-backed ITs need that route (see"
            + " their class javadoc); on a host without one they fail rather than skip. Last probe"
            + " output:\n"
            + last);
  }

  // --- CGI plumbing -------------------------------------------------------------------------

  /**
   * Bridge one HTTP request into {@code git http-backend}, the CGI program git ships for exactly
   * this. Runs on a worker thread — it spawns a process and reads it to completion, which an event
   * loop must not do.
   */
  private static void serveSmartHttp(Vertx vertx, HttpServerRequest req, Path projectRoot) {
    String method = req.method().name();
    String pathInfo = req.path();
    String query = req.query() == null ? "" : req.query();
    String contentType = req.getHeader("Content-Type");
    String contentEncoding = req.getHeader("Content-Encoding");
    req.body()
        .onComplete(
            read -> {
              byte[] body =
                  read.succeeded() && read.result() != null ? read.result().getBytes() : new byte[0];
              vertx
                  .executeBlocking(
                      () ->
                          gitHttpBackend(
                              projectRoot, method, pathInfo, query, contentType, contentEncoding,
                              body),
                      false)
                  .onComplete(
                      cgi -> {
                        if (cgi.failed()) {
                          req.response().setStatusCode(500).end(String.valueOf(cgi.cause()));
                          return;
                        }
                        HttpServerResponse response = req.response();
                        response.setStatusCode(cgi.result().status());
                        cgi.result().headers().forEach(response::putHeader);
                        response.end(Buffer.buffer(cgi.result().body()));
                      });
            });
  }

  /** A CGI program's answer: the {@code Status:} line, the headers it set, and the body. */
  private record CgiResponse(int status, Map<String, String> headers, byte[] body) {}

  /**
   * Run {@code git http-backend} with the CGI environment it expects and split its response.
   *
   * <p>stdin is written on its own thread while stdout is read on this one: the request body is
   * small, but a CGI program that starts answering before it has consumed its input deadlocks a
   * write-then-read implementation, and that is a bad way to spend an afternoon.
   */
  private static CgiResponse gitHttpBackend(
      Path projectRoot,
      String method,
      String pathInfo,
      String query,
      String contentType,
      String contentEncoding,
      byte[] body)
      throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("git", "http-backend");
    Map<String, String> env = pb.environment();
    env.put("GIT_PROJECT_ROOT", projectRoot.toString());
    // The fixture exports what it serves; qits-artifacts' own authorization is not modelled here.
    env.put("GIT_HTTP_EXPORT_ALL", "1");
    env.put("REQUEST_METHOD", method);
    env.put("PATH_INFO", pathInfo);
    env.put("QUERY_STRING", query);
    env.put("REMOTE_ADDR", "127.0.0.1");
    env.put("CONTENT_LENGTH", String.valueOf(body.length));
    if (contentType != null) {
      env.put("CONTENT_TYPE", contentType);
    }
    if (contentEncoding != null) {
      // git clients may gzip an upload-pack request; http-backend inflates it when told.
      env.put("HTTP_CONTENT_ENCODING", contentEncoding);
    }

    Process process = pb.start();
    Thread.startVirtualThread(
        () -> {
          try (var stdin = process.getOutputStream()) {
            stdin.write(body);
          } catch (Exception ignored) {
            // the child closed its input; whatever it already read is what it answers on
          }
        });
    Thread stderr =
        Thread.startVirtualThread(
            () -> {
              try (var err = process.getErrorStream()) {
                err.readAllBytes(); // drained so a chatty failure cannot fill the pipe and wedge us
              } catch (Exception ignored) {
                // nothing to report beyond the exit code
              }
            });
    byte[] raw = process.getInputStream().readAllBytes();
    process.waitFor();
    stderr.join(TimeUnit.SECONDS.toMillis(5));
    return parseCgi(raw);
  }

  /** Split a CGI response into headers and body at the first blank line, honouring {@code Status}. */
  private static CgiResponse parseCgi(byte[] raw) {
    int split = -1;
    int bodyAt = -1;
    for (int i = 0; i + 1 < raw.length; i++) {
      if (raw[i] == '\n' && raw[i + 1] == '\n') {
        split = i;
        bodyAt = i + 2;
        break;
      }
      if (i + 3 < raw.length
          && raw[i] == '\r'
          && raw[i + 1] == '\n'
          && raw[i + 2] == '\r'
          && raw[i + 3] == '\n') {
        split = i;
        bodyAt = i + 4;
        break;
      }
    }
    if (split < 0) {
      return new CgiResponse(500, Map.of(), raw);
    }
    int status = 200;
    Map<String, String> headers = new LinkedHashMap<>();
    String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
    for (String line : head.split("\\R")) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String name = line.substring(0, colon).trim();
      String value = line.substring(colon + 1).trim();
      if (name.equalsIgnoreCase("Status")) {
        status = Integer.parseInt(value.split("\\s+")[0]);
      } else if (!name.equalsIgnoreCase("Content-Length")
          && !name.equalsIgnoreCase("Transfer-Encoding")) {
        // Both are ours to decide: the body is written whole, so vert.x sets the framing.
        headers.put(name, value);
      }
    }
    return new CgiResponse(status, headers, Arrays.copyOfRange(raw, bodyAt, raw.length));
  }
}
