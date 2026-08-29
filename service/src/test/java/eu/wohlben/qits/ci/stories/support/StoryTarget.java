package eu.wohlben.qits.ci.stories.support;

import io.restassured.RestAssured;
import java.net.URI;

/**
 * The one launched process, addressed the way each of its planes is addressed — and named the way a
 * diagram names it.
 *
 * <p>qits-ci serves <b>three</b> shapes on one port and a story has to know which is which, because
 * they authenticate differently and only two of them are a story's own traffic:
 *
 * <ul>
 *   <li><b>{@code /ci/api/…}</b> — the JSON API, under {@code quarkus.rest.path}. Every route on it
 *       carries a class-level role ({@code qits:admin} or {@code qits:system}), so a story presents
 *       an identity on every call: a machine bearer for the trigger, forward-auth headers for the
 *       reads. See {@link StoryIdentities}.
 *   <li><b>{@code /ci/daemon}</b> — the control socket, a {@code @WebSocket} literal that does
 *       <em>not</em> follow {@code quarkus.rest.path} and therefore spells the {@code /ci} segment
 *       itself. It is the string every step container's daemon dials verbatim
 *       ({@code qits.ci.container-daemon-url}), which is why it is written here as an absolute
 *       literal rather than derived from anything.
 *   <li><b>{@code /ci/q/…}</b> — what Quarkus itself serves. The framework's RestAssured tap skips
 *       any path with a {@code /q/} segment, and this service's non-application root is
 *       {@code /ci/q}, so the shipped default is right here without an override.
 * </ul>
 *
 * <p>The <b>port is random</b> — failsafe launches the artifact with {@code
 * quarkus.http.test-port=0} — so nothing here is a constant except the paths. RestAssured is
 * configured with the port by the Quarkus integration-test extension, so an API call needs no base
 * url at all; only the socket does, and {@link #daemonSocket()} builds it from
 * {@link RestAssured#port} for exactly that reason.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-ci";

  /** {@code /ci/api} — {@code quarkus.rest.path}. A resource's {@code @Path} is relative to it. */
  public static final String API_PATH = "/ci/api";

  /** The one write: a domain event supplied by hand instead of by the bus. */
  public static final String TRIGGER_PATH = API_PATH + "/events/trigger";

  /** The run listing. Takes {@code ?repositoryId=} as a mandatory filter — ci owns no repository. */
  public static final String RUNS_PATH = API_PATH + "/runs";

  /** The distinct repository ids this instance has runs for. */
  public static final String REPOSITORIES_PATH = API_PATH + "/repositories";

  /** The id listing plus, per id, the newest run and the newest {@code main} run. */
  public static final String REPOSITORY_SUMMARY_PATH = REPOSITORIES_PATH + "/summary";

  /**
   * {@code /ci/daemon} — the control socket's literal. Spelled absolutely on purpose: it is a
   * cross-repo contract (the daemon binary dials it verbatim), so a story that addressed it
   * relatively would not catch a segment regression.
   */
  public static final String DAEMON_PATH = "/ci/daemon";

  private StoryTarget() {}

  /** One run, by id: {@code /ci/api/runs/<runId>}. */
  public static String runPath(String runId) {
    return RUNS_PATH + "/" + runId;
  }

  /** Where a step container's daemon dials, at this run's randomly chosen port. */
  public static URI daemonSocket() {
    return URI.create("http://localhost:" + RestAssured.port + DAEMON_PATH);
  }
}
