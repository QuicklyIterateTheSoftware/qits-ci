package eu.wohlben.qits.ci.githost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpGitHostRepoListing} on its own -- plain JUnit against a real server on a real socket,
 * no Quarkus, since its only collaborators are a plain {@code ObjectMapper} and a config string.
 * Same shape as {@code bus/EventsDaemonReleaseLogTest}, one port over.
 *
 * <p>What is under test is the port's contract rather than the happy path alone: every way the read
 * can fail answers the <b>empty</b> set, because the caller ({@code ListedAndKnownCiRepos}) turns
 * that into "the repositories qits-ci already knows" and a throw or a shrunk set would cost the
 * trigger engine its evaluation. Each failing case logs one WARN naming the url; the fallback is
 * what an assertion can hold, and it is the part a deployment depends on.
 */
public class HttpGitHostRepoListingTest {

  private Vertx vertx;
  private HttpServer server;
  private int port;

  /** Every request the stub answered -- the cache's assertion is a count. */
  private final AtomicInteger reads = new AtomicInteger();

  /** Every path the stub was asked for, so the url shape is asserted rather than assumed. */
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

  private volatile int status = 200;
  private volatile String body = "{\"repositories\":[]}";

  @BeforeEach
  void startStub() throws Exception {
    vertx = Vertx.vertx();
    server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          reads.incrementAndGet();
          paths.add(req.path());
          req.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(body);
        });
    port =
        server
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS)
            .actualPort();
  }

  @AfterEach
  void stopStub() {
    server.close();
    vertx.close();
  }

  @Test
  public void theListingIsReadFromTheGitSegmentOfTheConfiguredBase() {
    body = "{\"repositories\":[\"qits-ci\",\"qits-spa-home\"]}";

    assertEquals(Set.of("qits-ci", "qits-spa-home"), listing().repositories());
    assertEquals(List.of("/git"), paths, "the same segment ci appends to fetch a ref");
  }

  @Test
  public void aTrailingSlashOnTheConfiguredBaseDoesNotDoubleTheSegment() {
    body = "{\"repositories\":[\"qits-ci\"]}";

    assertEquals(Set.of("qits-ci"), listing("http://127.0.0.1:" + port + "/").repositories());
    assertEquals(List.of("/git"), paths);
  }

  @Test
  public void anEntryThatIsNotARepoIdIsSkippedRatherThanFailingTheListing() {
    // Every id here reaches a git argv, so the listing is filtered the same way ci's own bare-cache
    // directory names are -- and one bad entry must not cost the rest of the page.
    body = "{\"repositories\":[\"qits-ci\",\"../etc\",\"has space\",\"\",7,null]}";

    assertEquals(Set.of("qits-ci"), listing().repositories());
  }

  @Test
  public void anUnreachableGitHostAnswersEmptyRatherThanThrowing() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    } // closed immediately -- nothing listens here now, so the connection is refused

    assertTrue(listing("http://127.0.0.1:" + deadPort).repositories().isEmpty());
  }

  @Test
  public void aNon200AnswersEmpty() {
    status = 500;
    body = "{\"repositories\":[\"qits-ci\"]}";

    assertTrue(listing().repositories().isEmpty());
  }

  @Test
  public void aBodyThatIsNotJsonAnswersEmpty() {
    body = "not json at all";

    assertTrue(listing().repositories().isEmpty());
  }

  @Test
  public void aBodyWithoutARepositoriesArrayAnswersEmpty() {
    body = "{\"repos\":[\"qits-ci\"]}";

    assertTrue(listing().repositories().isEmpty());
  }

  @Test
  public void aGitHostThatIsNotHttpIsNotCalledAtAll() {
    // The suites' own stand-in is a file:// directory, which serves no listing and never could.
    assertTrue(listing("file:///tmp/qits-ci-test-git-host").repositories().isEmpty());
    assertEquals(0, reads.get(), "no socket is opened for a scheme that cannot answer");
  }

  @Test
  public void aSuccessfulListingStandsInForTheNextReadWithinItsTtl() {
    body = "{\"repositories\":[\"qits-ci\"]}";
    HttpGitHostRepoListing listing = listing();

    assertEquals(Set.of("qits-ci"), listing.repositories());
    body = "{\"repositories\":[\"qits-ci\",\"qits-cd\"]}";
    assertEquals(Set.of("qits-ci"), listing.repositories(), "still the cached answer");
    assertEquals(1, reads.get());
  }

  @Test
  public void aFailedReadIsNotCached() {
    // Otherwise a git host that came back up would stay invisible for the whole window.
    status = 500;
    HttpGitHostRepoListing listing = listing();
    assertTrue(listing.repositories().isEmpty());

    status = 200;
    body = "{\"repositories\":[\"qits-ci\"]}";
    assertEquals(Set.of("qits-ci"), listing.repositories());
    assertEquals(2, reads.get());
  }

  private HttpGitHostRepoListing listing() {
    return listing("http://127.0.0.1:" + port);
  }

  private HttpGitHostRepoListing listing(String gitHostUrl) {
    HttpGitHostRepoListing listing = new HttpGitHostRepoListing();
    listing.gitHostUrl = gitHostUrl;
    listing.objectMapper = new ObjectMapper();
    return listing;
  }
}
