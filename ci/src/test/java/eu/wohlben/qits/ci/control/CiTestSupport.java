package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.persistence.CiOwedEventRepository;
import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import eu.wohlben.qits.ci.persistence.CiStepRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for ci {@code @QuarkusTest}s: wipes the tables (steps first — FK) outside the test's own
 * transaction and resets the fakes, so every test starts from a clean slate (the {@code
 * ArtifactsTestSupport} pattern).
 *
 * <p>The release join's two tables are wiped here too, and the reason is the same one that makes the
 * trigger engine's shared candidate list bite: they outlive a run row on purpose, so a release fact
 * one test recorded would close another test's join and turn "held, nothing announced" into an
 * announcement nobody asked for.
 */
public abstract class CiTestSupport {

  @Inject protected CiRunRepository runs;
  @Inject protected CiStepRepository steps;
  @Inject protected CiReleaseAnnouncementRepository announcements;
  @Inject protected CiScmReleaseRepository scmReleases;
  @Inject protected CiOwedEventRepository owedEvents;
  @Inject protected FakeCiStepRunner fakeRunner;
  @Inject protected FakeCiConfigSource fakeConfig;
  @Inject protected FakeCandidateRepos fakeCandidates;
  @Inject protected CiRunService runService;
  @Inject protected CiEventTriggerParser triggerParser;

  /**
   * The event name every run this class drives is triggered by. A name of its own rather than a real
   * one, so a test that seeds a trigger file for a <em>real</em> event cannot be fired by one of
   * these and vice versa — the candidate list is shared for the life of a Quarkus instance, which is
   * the trap documented in {@code AGENTS.md} under the bus tests.
   */
  protected static final String TEST_EVENT_NAME = "CiTestEvent";

  /** The trigger file path these runs record. A real {@code ci-event-*.yml} name, as identity. */
  protected static final String TEST_TRIGGER_PATH = ".config/qits/ci-event-suite.yml";

  /**
   * Wraps a bare {@code steps:} document as the shortest trigger file that would declare it.
   *
   * <p><b>This is what replaced {@code CiRunService.execute}.</b> That entry accepted and ran a push
   * in one call, and half this suite used it as the cheap way into {@code runSteps} — the step state
   * machine, the announcer seams, the supersede rules — none of which is about pushes. The push arm
   * retired on 2026-09-05, so the same shortcut is spelled as the one trigger type there is. What
   * changes for a converted test is exactly two things, and both are the event path being the event
   * path: the row is {@code EVENT} with a {@code trigger_event_id}, and its step containers get the
   * four {@code QITS_EVENT_*} variables instead of an empty map.
   */
  protected static String triggerFile(String stepsYaml) {
    return "event: " + TEST_EVENT_NAME + "\n" + stepsYaml;
  }

  /** One resolved trigger, ready for {@code executeEventRun}/{@code onEventTrigger}. */
  protected CiRunService.EventRun eventRun(String repoId, String branch, String sha, String stepsYaml) {
    return eventRun(CiRepoRef.of(repoId), branch, sha, stepsYaml);
  }

  /** The same, for a repository carrying its public coordinate. */
  protected CiRunService.EventRun eventRun(
      CiRepoRef repo, String branch, String sha, String stepsYaml) {
    String content = triggerFile(stepsYaml);
    return new CiRunService.EventRun(
        repo,
        branch,
        sha,
        triggerParser.parse(TEST_TRIGGER_PATH, content),
        // A fresh event id per call, because two runs under one id are one announcement built twice
        // and the unique constraint on (trigger_event_id, repo_id, config_path) refuses the second.
        UUID.randomUUID().toString(),
        TEST_EVENT_NAME,
        Instant.now(),
        "{}",
        content);
  }

  /** Accept and run one pipeline synchronously, with no worker timing to wait out. */
  protected void executePipeline(String repoId, String branch, String sha, String stepsYaml) {
    runService.executeEventRun(eventRun(repoId, branch, sha, stepsYaml));
  }

  /**
   * Drop everything this test thread has already loaded, so the next read really goes to the
   * database.
   *
   * <p>Needed exactly when a test reads a row <em>before</em> the run worker changes it: a {@code
   * @QuarkusTest} method has one request-scoped persistence context, and Hibernate's identity map
   * wins over a query's own results — so a second read would hand back the stale instance the first
   * read cached and the test would be asserting against its own memory rather than the worker's
   * work.
   */
  protected void forgetLoadedEntities() {
    runs.getEntityManager().clear();
  }

  @BeforeEach
  void resetCiState() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              steps.deleteAll();
              runs.deleteAll();
              announcements.deleteAll();
              scmReleases.deleteAll();
              // The trigger engine's owed-event ledger, wiped for the release join's reason: a row
              // one test left owed would be re-evaluated by another test's sweep, against a
              // candidate list that has moved on.
              owedEvents.deleteAll();
            });
    fakeRunner.reset();
    fakeConfig.reset();
    // Empty by default, so no suite evaluates a trigger it did not ask for — the same reason the
    // eventstream module's recording raw listeners want nothing until a test arms them.
    fakeCandidates.reset();
  }
}
