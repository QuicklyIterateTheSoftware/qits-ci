package eu.wohlben.qits.ci.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.CiRepoRef;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpProjectsRepoListing} on its own — plain JUnit against a real server on a real socket,
 * no Quarkus, since its only collaborators are a plain {@code ObjectMapper} and a config value. The
 * same shape as {@code githost/HttpGitHostRepoListingTest}, one service over.
 *
 * <p>Two contracts are under test rather than one. The <b>read</b>: every way it can fail answers
 * the empty list, because the caller turns that into "the repositories qits-ci already knows" and a
 * throw or a shrunk set would cost the trigger engine its evaluation. And the <b>kill switch</b>: an
 * unset {@code qits.ci.projects-url} is not a failed read but a deployment that has not moved to the
 * catalogue, which must open no socket at all.
 */
public class HttpProjectsRepoListingTest {

  private Vertx vertx;
  private HttpServer server;
  private int port;

  /** Every request the stub answered — the cache's assertion is a count. */
  private final AtomicInteger reads = new AtomicInteger();

  /** Every path and role header seen, so the url shape and the identity are asserted, not assumed. */
  private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

  private final List<String> roles = Collections.synchronizedList(new ArrayList<>());

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
          roles.add(req.getHeader("X-Qits-Roles"));
          req.response()
              .setStatusCode(status)
              .putHeader("Content-Type", "application/json")
              .end(body);
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
  public void theCatalogueIsReadFromTheProjectsApiAndCarriesTheSystemRole() {
    body =
        "{\"repositories\":["
            + "{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\",\"mainBranch\":\"main\"},"
            + "{\"id\":\"b2\",\"projectId\":\"qits\",\"name\":\"qits-blobstore\"}]}";

    assertEquals(
        List.of(
            new CiRepoRef("a1", "qits", "qits-ci"), new CiRepoRef("b2", "qits", "qits-blobstore")),
        listing().repositories());
    assertEquals(List.of("/projects/api/repositories"), paths);
    assertEquals(List.of("qits:system"), roles, "the role the endpoint's contract asks for");
  }

  @Test
  public void aTrailingSlashOnTheConfiguredBaseDoesNotDoubleTheSegment() {
    body = "{\"repositories\":[{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"}]}";

    assertEquals(1, listing("http://127.0.0.1:" + port + "/").repositories().size());
    assertEquals(List.of("/projects/api/repositories"), paths);
  }

  @Test
  public void anEntryWithNoNameIsSkippedBecauseItHasNoPublicAddress() {
    // No name ⇒ no content route to read trigger files from. Answering it id-addressed would be a
    // read against the scheme that belongs to qits-projects alone after the cutover.
    body =
        "{\"repositories\":["
            + "{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"},"
            + "{\"id\":\"b2\",\"projectId\":\"qits\",\"name\":null},"
            + "{\"id\":\"c3\",\"projectId\":\"qits\"}]}";

    assertEquals(List.of(new CiRepoRef("a1", "qits", "qits-ci")), listing().repositories());
  }

  @Test
  public void anEntryThatCouldEscapeAUrlIsSkippedRatherThanFailingTheListing() {
    // All three values reach a url, so they are filtered exactly as the git host's ids are — and one
    // bad entry must not cost the rest of the page.
    body =
        "{\"repositories\":["
            + "{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"},"
            + "{\"id\":\"../etc\",\"projectId\":\"qits\",\"name\":\"x\"},"
            + "{\"id\":\"b2\",\"projectId\":\"../etc\",\"name\":\"x\"},"
            + "{\"id\":\"c3\",\"projectId\":\"qits\",\"name\":\"has space\"}]}";

    assertEquals(List.of(new CiRepoRef("a1", "qits", "qits-ci")), listing().repositories());
  }

  @Test
  public void anUnsetUrlIsNotConfiguredAndOpensNoSocket() {
    HttpProjectsRepoListing unset = new HttpProjectsRepoListing();
    unset.projectsUrl = Optional.empty();
    unset.objectMapper = new ObjectMapper();

    assertFalse(unset.configured(), "an unset key is the fallback arm, not a broken deployment");
    assertTrue(unset.repositories().isEmpty());
    assertEquals(0, reads.get());
  }

  @Test
  public void anUnreachableProjectsAnswersEmptyRatherThanThrowing() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    } // closed immediately — nothing listens here now, so the connection is refused

    assertTrue(listing("http://127.0.0.1:" + deadPort).repositories().isEmpty());
  }

  @Test
  public void aFailedReadAnswersEmpty() {
    // The contract says a failed read is a 5xx, and that is what must not shrink the candidate set.
    status = 503;
    body = "{\"repositories\":[{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"}]}";

    assertTrue(listing().repositories().isEmpty());
  }

  @Test
  public void aBodyThatIsNotJsonAnswersEmpty() {
    body = "not json at all";

    assertTrue(listing().repositories().isEmpty());
  }

  @Test
  public void aBodyWithoutARepositoriesArrayAnswersEmpty() {
    body = "{\"repos\":[]}";

    assertTrue(listing().repositories().isEmpty());
  }

  @Test
  public void aProjectsUrlThatIsNotHttpIsNotCalledAtAll() {
    assertTrue(listing("file:///tmp/qits-projects").repositories().isEmpty());
    assertEquals(0, reads.get(), "no socket is opened for a scheme that cannot answer");
  }

  @Test
  public void aSuccessfulListingStandsInForTheNextReadWithinItsTtl() {
    body = "{\"repositories\":[{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"}]}";
    HttpProjectsRepoListing listing = listing();

    assertEquals(1, listing.repositories().size());
    body =
        "{\"repositories\":["
            + "{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"},"
            + "{\"id\":\"b2\",\"projectId\":\"qits\",\"name\":\"qits-cd\"}]}";
    assertEquals(1, listing.repositories().size(), "still the cached answer");
    assertEquals(1, reads.get());
  }

  @Test
  public void aFailedReadIsNotCached() {
    // Otherwise a qits-projects that came back up would stay invisible for the whole window.
    status = 503;
    HttpProjectsRepoListing listing = listing();
    assertTrue(listing.repositories().isEmpty());

    status = 200;
    body = "{\"repositories\":[{\"id\":\"a1\",\"projectId\":\"qits\",\"name\":\"qits-ci\"}]}";
    assertEquals(1, listing.repositories().size());
    assertEquals(2, reads.get());
  }

  private HttpProjectsRepoListing listing() {
    return listing("http://127.0.0.1:" + port);
  }

  private HttpProjectsRepoListing listing(String projectsUrl) {
    HttpProjectsRepoListing listing = new HttpProjectsRepoListing();
    listing.projectsUrl = Optional.of(projectsUrl);
    listing.objectMapper = new ObjectMapper();
    return listing;
  }
}
