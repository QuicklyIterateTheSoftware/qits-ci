package eu.wohlben.qits.ci.daemonhost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A qits-containers that never leaves this JVM: the JDK's own {@link HttpServer} on an ephemeral
 * port, answering whatever a test scripts and recording exactly what arrived.
 *
 * <p><b>A second copy of the client jar's own stub</b>, trimmed, and duplicated for the reason both
 * {@code FakeCiStepRunner}s and this module's {@code StubEventsServer} are: the modules do not share
 * a test classpath, that one is package-private in a jar this repository consumes, and a test-jar to
 * bridge sixty lines would couple this suite to another repository's fixtures. The JDK server rather
 * than Vert.x, for the same reason it uses one — no web stack has to be dragged in to prove a header
 * went out.
 *
 * <p><b>Deliberately dumb.</b> It routes nothing, validates nothing and holds no registry: a test
 * says which status and which body it wants back, and what is under test is the launcher's reading
 * of the four answers those become. What the real routes answer is proved against the real service
 * in qits-containers' own suite; this stub exists for the cases a real service will not produce on
 * demand — a 503, a connection nothing accepts — and for reading the request line off the wire.
 */
final class StubContainersServer implements AutoCloseable {

  /**
   * One request that arrived, as the stub saw it.
   *
   * <p><b>{@code path} and {@code query} are the RAW forms</b>, percent-escapes and all: {@code
   * URI.getPath()} decodes, so a stub reading it would report a traversal for a request that
   * carried it safely encoded — the client's encoding made invisible by the assertion meant to check
   * it. What is on the wire is what the service routes on.
   */
  record Received(
      String method, String path, String query, Map<String, String> headers, String body) {}

  /**
   * One answer a test has queued up. A {@code status} of {@link #NO_ANSWER} is the absence of one:
   * the request is recorded and the connection closed with nothing on it.
   */
  private record Scripted(int status, String body) {}

  /**
   * The scripted "nothing answered" — a connection dropped before a status line, which is what the
   * client reads as {@code Unreachable}. The only other way to stage that is an address nothing
   * listens on, and an address cannot answer the <em>second</em> request of a retry.
   */
  private static final int NO_ANSWER = 0;

  private final HttpServer server;

  private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

  private final Deque<Scripted> scripted = new ArrayDeque<>();

  /** What is answered once the script runs out: an empty JSON object, which binds to most records. */
  private Scripted fallback = new Scripted(200, "{}");

  StubContainersServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  /** Where this stub answers: scheme + host + port, no path — what the client's base URL is. */
  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Queue one answer. They are handed out in order, then the fallback repeats. */
  StubContainersServer script(int status, String body) {
    synchronized (scripted) {
      scripted.add(new Scripted(status, body));
    }
    return this;
  }

  /** Queue one dropped connection, in the same order as the scripted answers. */
  StubContainersServer scriptSilence() {
    return script(NO_ANSWER, null);
  }

  /** What every unscripted request gets. */
  StubContainersServer fallback(int status, String body) {
    fallback = new Scripted(status, body);
    return this;
  }

  List<Received> received() {
    return List.copyOf(received);
  }

  /** The last request, which is what a single-call test asserts on. */
  Received last() {
    List<Received> all = received();
    if (all.isEmpty()) {
      throw new AssertionError("nothing reached the stub");
    }
    return all.getLast();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      exchange
          .getRequestHeaders()
          .forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.getFirst()));
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(
          new Received(
              exchange.getRequestMethod(),
              exchange.getRequestURI().getRawPath(),
              exchange.getRequestURI().getRawQuery(),
              headers,
              body));

      Scripted answer;
      synchronized (scripted) {
        answer = scripted.isEmpty() ? fallback : scripted.poll();
      }
      if (answer.status() == NO_ANSWER) {
        // Close with no status line at all. The exchange's own close() is what does it, so nothing
        // here writes a response the client could bind.
        return;
      }
      byte[] out =
          answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      // -1 is the JDK server's "no body at all", which is what a 204 has to be: a content-length of
      // zero on a 204 is a header the client would read as a body it should try to bind.
      exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
      if (out.length > 0) {
        exchange.getResponseBody().write(out);
      }
    }
  }
}
