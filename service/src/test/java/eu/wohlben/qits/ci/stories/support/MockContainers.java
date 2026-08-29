package eu.wohlben.qits.ci.stories.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * qits-containers, played by a recording stand-in — the service that owns the docker daemon, and
 * therefore the service a step container has to be asked for.
 *
 * <p><b>qits-ci holds no docker socket and spawns no process.</b> Putting a step container
 * somewhere is four HTTP calls to this address, and the only two a story sees are {@code
 * PUT …/{ref}} (start it) and {@code DELETE …/{ref}} (take it away). So a stand-in that answers
 * those two is the whole of what stands between a story and a real build — no docker, no image, no
 * published daemon binary.
 *
 * <h2>It is not a mock: it is where the step's credentials come from</h2>
 *
 * <p>The {@code PUT}'s body is the workload spec, and the spec's environment carries {@code
 * QITS_CI_DAEMON_ID} and {@code QITS_CI_DAEMON_SECRET} — the per-container credential qits-ci mints
 * for this one step and hands to nobody else. A real daemon reads them out of its own environment
 * and dials back with them. {@link #awaitLaunchEnvironment} reads them out of the recorded spec,
 * which is what lets {@link StoryDaemon} be a real client of the real socket rather than a
 * fixture with privileged access to the host's registry: the story learns the secret exactly the
 * way a container learns it, and by no other route.
 *
 * <p>{@code eu.wohlben.qits.servicemock.MockService} could not serve this. It records method, path,
 * query and status — everything a diagram needs and nothing a credential lives in — and the body is
 * the entire point here.
 *
 * <h2>The recording is a file, and the boot reap is not in it</h2>
 *
 * <p>Same arrangement, and the same reason, as {@link StoryGitHost}: this server is started from a
 * {@code QuarkusTestProfile}, which is instantiated in more than one classloader, so the port is
 * parked in a system property and the recording is a file both copies resolve identically.
 *
 * <p>{@link #installSource()} skips the one call that is nobody's story: qits-ci's boot reap, a
 * {@code DELETE} of the whole {@code (owner, workload)} collection that a {@code StartupEvent}
 * observer makes before any story exists. Every call a story causes addresses one place and carries
 * a {@code ref}, so the two are told apart by the shape of the path — the service's own
 * distinction, not one invented here.
 */
public final class MockContainers {

  /** How a diagram names the service this stand-in impersonates. */
  public static final String SERVICE_NAME = "qits-containers";

  /** The client's route root, exactly as {@code ContainersClient.CONTAINERS_PATH} spells it. */
  public static final String CONTAINERS_PATH = "/containers/api/containers/";

  /** The workload every step container belongs to — {@code CiDaemonLauncher.WORKLOAD}. */
  public static final String WORKLOAD = "ci-step";

  /**
   * Who qits-ci is to the orchestrator: {@code qits.ci.containers.owner}, which defaults to reading
   * {@code quarkus.oidc-client.client-id}. It is in the path of every call, so a diagram's label
   * carries it and a story has to spell it.
   */
  public static final String OWNER = "qits-ci";

  /** Where the port is parked so a second classloader's copy attaches instead of starting a second. */
  private static final String PORT_PROPERTY = "qits.stories.mock-containers.port";

  private static final String SOURCE_ID = "mock-containers";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Object LOCK = new Object();

  private static HttpServer server;

  private static boolean registered;

  private static int floor;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private MockContainers() {}

  // --- lifecycle ---------------------------------------------------------------------------------

  /**
   * Start once per JVM and answer where it listens. Safe to call from a profile's {@code
   * getConfigOverrides()}: the port has to exist before the application boots, which is the only
   * moment a launched process can be told about it.
   */
  public static synchronized String baseUrl() {
    String parked = System.getProperty(PORT_PROPERTY);
    if (parked != null) {
      return "http://127.0.0.1:" + parked;
    }
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException notStarted) {
      throw new UncheckedIOException("could not start the qits-containers stand-in", notStarted);
    }
    server.createContext("/", MockContainers::handle);
    server.start();
    int port = server.getAddress().getPort();
    System.setProperty(PORT_PROPERTY, String.valueOf(port));
    return "http://127.0.0.1:" + port;
  }

  /** Where every recorded call is appended — see the class javadoc on why it is a file. */
  public static Path requestLog() {
    return Path.of(System.getProperty("user.dir"), "target", "story-containers.log");
  }

  // --- what a story class calls -------------------------------------------------------------------

  /**
   * Register the recording as a cumulative {@link NetworkCapture} source, once per JVM, taking the
   * current end of it as the floor. The boot reap is below that floor whenever a story class
   * installs after boot, and skipped by shape in any case.
   */
  public static void installSource() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      floor = readLines().size();
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, MockContainers::edges);
      registered = true;
    }
  }

  /**
   * One step container qits-ci asked for: the name it gave the place, and the environment it put in
   * the spec.
   *
   * <p>The name is <b>read back off the wire</b> rather than recomputed. {@code
   * CiDaemonLauncher.containerName} is package-private and derives the name from the run id and its
   * hash; a mirror of that rule in the test sources would be a second place for it to live, and one
   * that keeps passing while the real one changes. What a story needs is the place qits-ci actually
   * addressed, which is exactly what arrived here.
   */
  public record Launch(String containerName, Map<String, String> environment) {}

  /** How many launches have already been handed to a story — see {@link #awaitLaunch}. */
  private static int consumedLaunches;

  /**
   * The next step container qits-ci asked for — waited for, because the launch happens on the run
   * worker some time after the trigger answered.
   *
   * <p>Each call consumes one, so a second story's launch is that story's rather than a re-read of
   * the first one's. The catalogue runs its builds one at a time (the run worker is single-threaded
   * and the pool is one deep here), so "the next one" is unambiguous.
   *
   * <p>Fails loudly on timeout, unlike the deliberately silent {@code await*} helpers elsewhere:
   * this one is not a latency hedge but the story's only route to the credential, and a story that
   * carried on without it would dial the socket with nulls and fail three assertions later on a
   * refusal that says nothing about the cause.
   */
  public static Launch awaitLaunch(Duration patience) {
    long deadline = System.nanoTime() + patience.toNanos();
    String prefix = CONTAINERS_PATH + OWNER + "/" + WORKLOAD + "/";
    while (true) {
      int seen = 0;
      synchronized (LOCK) {
        for (String line : readLines()) {
          String[] fields = line.split("\t", -1);
          if (fields.length != 5 || !fields[0].equals("PUT") || !fields[1].startsWith(prefix)) {
            continue;
          }
          if (seen++ < consumedLaunches) {
            continue;
          }
          consumedLaunches++;
          return new Launch(fields[1].substring(prefix.length()), environmentOf(fields[4]));
        }
      }
      if (System.nanoTime() >= deadline) {
        throw new AssertionError(
            "qits-ci never asked qits-containers for a step container within " + patience);
      }
      sleep();
    }
  }

  /**
   * Wait, briefly and silently, for the {@code DELETE} that takes one step container away.
   *
   * <p>It is the last thing a step does and it happens in a {@code finally} on the run worker, so
   * it readily lands <em>after</em> the story's last assertion — which would put it in the next
   * story's diagram. A story therefore awaits it before returning, exactly as the framework's
   * contract for async far-side traffic requires.
   */
  public static void awaitRemoved(String containerName, Duration patience) {
    long deadline = System.nanoTime() + patience.toNanos();
    String place = CONTAINERS_PATH + OWNER + "/" + WORKLOAD + "/" + containerName;
    while (true) {
      for (String line : readLines()) {
        String[] fields = line.split("\t", -1);
        if (fields.length == 5 && fields[0].equals("DELETE") && fields[1].equals(place)) {
          return;
        }
      }
      if (System.nanoTime() >= deadline) {
        return;
      }
      sleep();
    }
  }

  /** The label one call renders as, with the container's own name templated — see {@link #edge}. */
  public static String label(String method, String query, int status) {
    return method
        + " "
        + CONTAINERS_PATH
        + OWNER
        + "/"
        + WORKLOAD
        + "/{container}"
        + (query == null || query.isEmpty() ? "" : "?" + query)
        + " -> "
        + status;
  }

  // --- the server --------------------------------------------------------------------------------

  private static void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getRawPath();
      String query = exchange.getRequestURI().getRawQuery();
      String method = exchange.getRequestMethod();
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Answer answer = answer(method, path);
      record(method, path, query, answer.status(), body);
      byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(answer.status(), out.length);
      try (OutputStream stream = exchange.getResponseBody()) {
        stream.write(out);
      }
    }
  }

  private record Answer(int status, String body) {}

  /**
   * The three answers this stand-in gives, and each is the shape the client binds.
   *
   * <p>An {@code ensure} answers a state of {@code RUNNING}: the wire contract is explicit that a
   * 2xx whose container did not start is a <em>true answer</em> carrying {@code MISSING}, and
   * qits-ci reads it that way, so a stand-in that answered {@code {}} would be leaving the field
   * this decision turns on unset.
   */
  private static Answer answer(String method, String path) {
    if (!path.startsWith(CONTAINERS_PATH)) {
      return new Answer(404, "{\"detail\":\"no such route in the qits-containers stand-in\"}");
    }
    String[] segments = path.substring(CONTAINERS_PATH.length()).split("/");
    boolean onePlace = segments.length == 3;
    if ("PUT".equals(method) && onePlace) {
      return new Answer(
          200,
          "{\"containerName\":\""
              + segments[2]
              + "\",\"state\":{\"observed\":\"RUNNING\"},\"created\":true}");
    }
    if ("DELETE".equals(method) && onePlace) {
      return new Answer(200, "{\"containerName\":\"" + segments[2] + "\",\"existed\":true}");
    }
    if ("DELETE".equals(method) && segments.length == 2) {
      // The boot reap: this owner's whole ci-step collection, created before an instant.
      return new Answer(200, "{\"destroyed\":[]}");
    }
    return new Answer(404, "{\"detail\":\"no such route in the qits-containers stand-in\"}");
  }

  /**
   * Append one answered call. Tab-separated because a workload spec is compact JSON on one line and
   * a tab cannot occur in it unescaped; a stray newline is folded so one call stays one line.
   */
  private static synchronized void record(
      String method, String path, String query, int status, String body) {
    String line =
        method
            + "\t"
            + path
            + "\t"
            + (query == null ? "" : query)
            + "\t"
            + status
            + "\t"
            + body.replace("\n", " ").replace("\r", " ")
            + "\n";
    try {
      Path log = requestLog();
      Files.createDirectories(log.getParent());
      Files.writeString(
          log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException unwritable) {
      // Documentation, never a precondition of answering — see StubGitHost.record.
    }
  }

  // --- the source ---------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = readLines();
    if (harvested > lines.size()) {
      harvested = 0;
      floor = 0;
      lines = readLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /**
   * One recorded call as an edge, or nothing when it is the boot reap.
   *
   * <p><b>The container's name is templated by hand.</b> {@code qits-ci-<run>-<hash>-<index>} is not
   * a whole UUID, a long hex run or a bare number, so the default scrubber leaves it exactly as it
   * is — and a label carrying a per-run name would move the story's {@code networkHash} on every
   * run, whose only symptom is a hash that never settles. What the diagram is about is that a step
   * container was asked for and taken away, which {@code {container}} says and a generated name
   * does not.
   */
  private static Optional<NetworkEdge> edge(String line) {
    String[] fields = line.split("\t", -1);
    if (fields.length != 5 || !fields[1].startsWith(CONTAINERS_PATH)) {
      return Optional.empty();
    }
    String[] segments = fields[1].substring(CONTAINERS_PATH.length()).split("/");
    if (segments.length != 3) {
      // The collection-scoped boot reap. Setup, not a walk anybody takes.
      return Optional.empty();
    }
    return Optional.of(
        NetworkEdge.http(
            StoryTarget.SERVICE,
            SERVICE_NAME,
            label(fields[0], fields[2], Integer.parseInt(fields[3]))));
  }

  private static Map<String, String> environmentOf(String specBody) {
    try {
      JsonNode env = MAPPER.readTree(specBody).path("spec").path("env");
      Map<String, String> environment = new LinkedHashMap<>();
      env.properties().forEach(entry -> environment.put(entry.getKey(), entry.getValue().asText()));
      return environment;
    } catch (IOException unreadable) {
      throw new AssertionError("the recorded workload spec was not JSON: " + specBody, unreadable);
    }
  }

  private static List<String> readLines() {
    List<String> all = allLines();
    return floor >= all.size() ? List.of() : all.subList(floor, all.size());
  }

  private static List<String> allLines() {
    Path file = requestLog();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
