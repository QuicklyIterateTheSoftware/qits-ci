package eu.wohlben.qits.ci.bus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.ci.githost.StubGitHost;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The push adapter's own decisions: which arriving {@code SCMPublishCommit} becomes a run, and which
 * one is settled without becoming anything.
 *
 * <p><b>What this class covers is what the endpoint's tests used to.</b> {@code
 * CiPipelineBoundaryTest} still owns the loop from a push to the read surface — it now starts at
 * this listener instead of at a POST, so its green/red/dedupe/supersede cases are the listener path
 * without a line of them changing meaning — and {@code DurableBusConsumptionTest} owns the claim
 * ledger. What is left, and is here, is the three answers only this bean gives: a push builds, a
 * suppressed push does not, and a push naming something {@code CiIdentifiers} refuses is swallowed
 * rather than retried forever.
 *
 * <p><b>Every "no run" assertion is made immediately after {@code onFrame} returns, and that is what
 * makes it a claim rather than a race.</b> Accepting a push writes its row synchronously — {@code
 * CiRunService.onPostReceive} inserts before it returns — so a listener that had accepted this push
 * would already have left a {@code QUEUED} row to find. Polling for the absence of one would prove
 * nothing: an accepted run against an unreadable repository is discarded a moment later and leaves
 * the same empty listing.
 *
 * <p>Drives the bean directly rather than through {@code DurableFunnel}, because the bus is dark in
 * this suite and the handler is what is under test. The funnel's answers — handled, skipped, owed —
 * are asserted where the bus is on.
 */
@QuarkusTest
@WithTestResource(value = StubGitHost.class, scope = TestResourceScope.GLOBAL)
public class ScmPublishCommitListenerTest {

  /** A pipeline that needs no container: accepted, run, green. */
  private static final String CONFIG = "steps: []\n";

  @Inject ScmPublishCommitListener listener;

  @Test
  public void aPushBecomesARunCausedByTheEventThatAnnouncedIt() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "scm-green", CONFIG);
    EventFrame frame = ScmPushFrames.push(repoId, "scm-green", ScmPushFrames.ZERO_SHA, sha);

    listener.onFrame(frame);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(sha, run.get("commitSha"));
    assertEquals("scm-green", run.get("branch"));
    assertEquals(
        "POST_RECEIVE",
        run.get("triggerType"),
        "a push is still a push; only the transport under it changed");
    // The row is where causation waits for the run worker: CausationScope is a ThreadLocal and the
    // publish happens on another thread, minutes later, possibly after a restart. What that publish
    // is stamped with is asserted end to end in CiEventTriggerCausationTest.
    assertEquals(
        frame.id(),
        run.get("triggerEventId"),
        "the announcing event is the run's cause, and the row is how it survives the hand-off");
  }

  /**
   * The name-addressed push: the event carries the public {@code (projectId, repoName)} pair the git
   * host filled in from the address it arrived on, and both land on the run row.
   *
   * <p>The bare is stored under an opaque id and the git host resolves the pair to it, so this also
   * shows the config read really went out name-addressed — an id-addressed read would have found the
   * bare too, but the alias is the only thing that makes {@code /git/<project>/<name>} resolve.
   */
  @Test
  public void aNameAddressedPushRecordsTheProjectAndTheName() throws Exception {
    String repoId = seedOrigin();
    String repoName = "named-" + repoId.substring(0, 8);
    StubGitHost.alias("qits", repoName, repoId);
    String sha = pushBranchWithConfig(repoId, "scm-named", CONFIG);

    listener.onFrame(
        ScmPushFrames.named(repoId, "qits", repoName, "scm-named", ScmPushFrames.ZERO_SHA, sha));

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals("qits", run.get("projectId"));
    assertEquals(repoName, run.get("repoName"));
    assertEquals(repoId, run.get("repoId"), "the storage id stays the key the row is found by");
  }

  /**
   * The compatibility arm: a push announced with no name pair — an id-addressed push, a mirror sync,
   * anything a pre-cutover git host published — records nulls and is built exactly as before.
   */
  @Test
  public void anIdAddressedPushRecordsNoNamesAndStillBuilds() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "scm-unnamed", CONFIG);

    listener.onFrame(ScmPushFrames.push(repoId, "scm-unnamed", ScmPushFrames.ZERO_SHA, sha));

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertNull(run.get("projectId"), "an id-addressed push names no project");
    assertNull(run.get("repoName"), "and no repository name");
  }

  /**
   * One announced push is one run, whatever mix of live frame and catch-up row produced the
   * arrivals. The claim ledger settles this before the listener is called twice at all — {@code
   * DurableBusConsumptionTest} asserts that half — so what is under test here is the net underneath
   * it: the unique constraint on {@code (trigger_event_id, repo_id, config_path)}, which lives on
   * ci's own datasource where the claim does not.
   *
   * <p><b>The duplicate is settled, not thrown.</b> A throw would leave the push owed forever over a
   * run that already exists, and the second offer really is handled: the answer is "this is already
   * recorded".
   */
  @Test
  public void oneAnnouncedPushIsOneRunEvenIfTheHandlerRunsTwice() throws Exception {
    String repoId = seedOrigin();
    String sha = pushBranchWithConfig(repoId, "scm-once", CONFIG);
    EventFrame frame = ScmPushFrames.push(repoId, "scm-once", ScmPushFrames.ZERO_SHA, sha);

    listener.onFrame(frame);
    listener.onFrame(frame);

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(1, listRuns(repoId).size(), "the second delivery must not build the commit again");
  }

  /**
   * {@code -o qits.no-ci}, which the git host used to apply on its consumers' behalf by not POSTing
   * at all. It is a fact on the event now, and this is what a run engine does with it.
   *
   * <p>The same repository is pushed again without the flag afterwards, so "no run" is a statement
   * about the flag rather than about a fixture that could never have built anything.
   */
  @Test
  public void aSuppressedPushRecordsNoRunAtAll() throws Exception {
    String repoId = seedOrigin();
    String suppressed = pushBranchWithConfig(repoId, "scm-no-ci", CONFIG);

    listener.onFrame(
        ScmPushFrames.suppressed(repoId, "scm-no-ci", ScmPushFrames.ZERO_SHA, suppressed));

    assertTrue(
        listRuns(repoId).isEmpty(),
        "an accepted push writes its row before onFrame returns, so any row here is one too many");

    String built = pushBranchWithConfig(repoId, "scm-ci", CONFIG);
    listener.onFrame(ScmPushFrames.push(repoId, "scm-ci", ScmPushFrames.ZERO_SHA, built));

    Map<String, Object> run = awaitTerminalRun(repoId);
    assertEquals("SUCCESS", run.get("status"));
    assertEquals(built, run.get("commitSha"), "only the push that was not suppressed built");
  }

  /**
   * The poison case. A sha that is not a sha is the same bytes on every offer, so the handler must
   * settle it: throwing would hand this push back forever and hold this consumer's watermark behind
   * it, and there is no caller left to answer 400.
   */
  @Test
  public void aPushNamingAShaThisServiceRefusesIsSettledUnbuilt() throws Exception {
    String repoId = seedOrigin();
    EventFrame malformed =
        ScmPushFrames.push(repoId, "scm-bad-sha", ScmPushFrames.ZERO_SHA, "../../etc/passwd");

    listener.onFrame(malformed);

    assertTrue(listRuns(repoId).isEmpty(), "a refused identifier must not reach a run row");
  }

  /** The same, one field over: a branch name that could escape a path. */
  @Test
  public void aPushNamingABranchThisServiceRefusesIsSettledUnbuilt() throws Exception {
    String repoId = seedOrigin();

    listener.onFrame(ScmPushFrames.push(repoId, "-not a branch", ScmPushFrames.ZERO_SHA, "a".repeat(40)));

    assertTrue(listRuns(repoId).isEmpty());
  }

  /** A payload that will not bind at all — settled, not owed, and nothing recorded. */
  @Test
  public void aPushWithAnUnreadablePayloadIsSettledUnbuilt() throws Exception {
    String repoId = seedOrigin();
    EventFrame broken =
        new EventFrame(
            UUID.randomUUID().toString(), "SCMPublishCommit", null, "not json at all", null, null);

    listener.onFrame(broken);

    assertTrue(listRuns(repoId).isEmpty());
  }

  // --- the read surface ---

  private List<Map<String, Object>> listRuns(String repoId) {
    return given()
        .when()
        .get("/ci/api/runs?repositoryId=" + repoId)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("runs");
  }

  private Map<String, Object> awaitTerminalRun(String repoId) throws Exception {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> runs = listRuns(repoId);
      if (runs.size() == 1 && !List.of("QUEUED", "RUNNING").contains(runs.get(0).get("status"))) {
        return runs.get(0);
      }
      Thread.sleep(100);
    }
    return fail("no terminal CI run for " + repoId + " within the deadline");
  }

  // --- git plumbing (StubGitHost serves these bares as <base>/git/<repoId>) ---

  private Path gitHostRoot() {
    return StubGitHost.ROOT.resolve("git");
  }

  private String seedOrigin() throws Exception {
    String repoId = UUID.randomUUID().toString();
    Path seed = Files.createTempDirectory("ci-scm-seed");
    git(seed, "init", "-q", "-b", "main");
    Files.writeString(seed.resolve("hello.txt"), "hello\n");
    commitAll(seed, "initial");

    Path origin = gitHostRoot().resolve(repoId);
    Files.createDirectories(origin.getParent());
    git(null, "clone", "-q", "--bare", seed.toString(), origin.toString());
    return repoId;
  }

  private String pushBranchWithConfig(String repoId, String branch, String config) throws Exception {
    Path clone = Files.createTempDirectory("ci-scm-clone");
    Files.delete(clone); // git clone wants to create the target itself
    git(null, "clone", "-q", gitHostRoot().resolve(repoId).toString(), clone.toString());
    if (!"main".equals(branch)) {
      git(clone, "checkout", "-q", "-b", branch);
    }
    Path configFile = clone.resolve(".config/qits/ci-post-receive.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, config);
    Files.writeString(clone.resolve("branch.txt"), branch + " " + UUID.randomUUID() + "\n");
    commitAll(clone, "add ci config");
    String sha = git(clone, "rev-parse", "HEAD").trim();
    git(clone, "push", "-q", "origin", branch);
    return sha;
  }

  private void commitAll(Path clone, String message) throws Exception {
    git(clone, "add", ".");
    git(clone, "-c", "user.email=ci@test", "-c", "user.name=ci", "commit", "-q", "-m", message);
  }

  private String git(Path cwd, String... args) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    if (cwd != null) {
      builder.directory(cwd.toFile());
    }
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
    }
    return output;
  }
}
