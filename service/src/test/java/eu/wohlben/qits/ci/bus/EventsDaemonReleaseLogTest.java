package eu.wohlben.qits.ci.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.ci.control.DaemonReleaseLog.Release;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EventsDaemonReleaseLog} on its own -- plain JUnit, no Quarkus, since its only
 * collaborators are a plain {@code ObjectMapper} and a config string it reads once. The parsing
 * happy path is proven end to end by {@code DaemonReleaseListenerTest} against {@link
 * StubEventsServer}; this is the one claim that needs no server at all: {@link
 * eu.wohlben.qits.ci.control.DaemonReleaseLog#recentReleases}'s contract that an unreachable
 * qits-events answers empty rather than throwing (ci-daemon-autoadopt-plan.md §2.6 -- "leave the
 * ladder exactly as it was, not empty it").
 */
public class EventsDaemonReleaseLogTest {

  @Test
  public void anUnreachableEventsServiceAnswersEmptyRatherThanThrowing() throws Exception {
    int deadPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      deadPort = socket.getLocalPort();
    } // closed immediately -- nothing listens here now, so the connection is refused

    EventsDaemonReleaseLog log = new EventsDaemonReleaseLog();
    log.eventsUrl = "http://127.0.0.1:" + deadPort;
    log.objectMapper = new ObjectMapper();

    List<Release> releases = log.recentReleases(2);
    assertTrue(releases.isEmpty(), releases.toString());
  }

  @Test
  public void aBodyThatIsNotJsonParsesToNoReleasesRatherThanThrowing() {
    EventsDaemonReleaseLog log = new EventsDaemonReleaseLog();
    log.objectMapper = new ObjectMapper();

    assertEquals(List.of(), log.parse("not json at all"));
  }

  @Test
  public void aRowWithNoVersionIsSkippedRatherThanFailingTheWholePage() {
    EventsDaemonReleaseLog log = new EventsDaemonReleaseLog();
    log.objectMapper = new ObjectMapper();

    String body =
        "{\"events\":[{\"id\":\"e1\",\"occurredAt\":\"2026-08-01T00:00:00Z\",\"payload\":"
            + "\"{\\\"packageName\\\":\\\"qits-ci-daemon\\\",\\\"packageType\\\":\\\"daemon\\\"}\"},"
            + "{\"id\":\"e2\",\"occurredAt\":\"2026-08-02T00:00:00Z\",\"payload\":"
            + "\"{\\\"packageName\\\":\\\"qits-ci-daemon\\\",\\\"packageType\\\":\\\"daemon\\\","
            + "\\\"version\\\":\\\"2026.803.91607\\\"}\"}],\"nextCursor\":null}";

    List<Release> releases = log.parse(body);
    assertEquals(1, releases.size(), releases.toString());
    assertEquals("2026.803.91607", releases.get(0).version());
    assertEquals("e2", releases.get(0).eventId());
  }
}
