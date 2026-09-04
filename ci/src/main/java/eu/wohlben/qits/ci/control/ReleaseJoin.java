package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.entity.CiReleaseAnnouncement;
import eu.wohlben.qits.ci.entity.CiScmRelease;
import eu.wohlben.qits.ci.persistence.CiReleaseAnnouncementRepository;
import eu.wohlben.qits.ci.persistence.CiScmReleaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The join that decides whether a green release pipeline may announce a {@code SoftwareRelease}:
 * <b>both</b> a green release-pipeline run for a {@code (repository, version)} and an {@code
 * SCMRelease} for that same pair, in either order, or no announcement at all.
 *
 * <h2>Why the second fact exists</h2>
 *
 * <p>A green release-recipe run used to announce one {@code SoftwareRelease} per {@code artifacts:}
 * entry off <em>any</em> trigger. A bootstrap replay pushes a release tag without releasing anything
 * — the tag is a durable fact being restored, not novelty being announced — so every rebootstrap
 * impersonated a release: the train woke, every consumer ran a bump, and each bump ended in a release
 * call against a qits-workspaces the boot had not deployed yet. bootstrap-replay-plan.md's WP2.
 *
 * <p>The vocabulary already draws the line. A <b>restore</b> re-establishes SCM state, so it produces
 * only {@code SCMPublishTag}. A <b>release</b> additionally announces novelty, and {@code SCMRelease}
 * is that announcement — <b>only qits-workspaces publishes it</b>. So a real release produces both
 * events and a replay produces one, and this class is that difference made into a rule.
 *
 * <h2>The key, and where each side derives it</h2>
 *
 * <p>{@code (repository, version)}, one key with two producers:
 *
 * <ul>
 *   <li><b>The run.</b> {@code repository} is {@code ci_run.repo_id}, the repository whose pipeline
 *       published. {@code version} is read out of the payload of the event that triggered the run —
 *       {@code version} on an {@code SCMRelease}, {@code tagName} on an {@code SCMPublishTag}, which
 *       IS the version string, since a release stamp is the name of the tag the release push
 *       created. {@code CiRunService.releaseVersionOf} is the one place that choice is made.
 *   <li><b>The release.</b> {@code repository} and {@code version} off the {@code SCMRelease}
 *       payload, with {@code repositoryName} recorded beside the id as a second spelling — see
 *       {@link CiScmRelease}.
 * </ul>
 *
 * <h2>Arrival order, and why neither half may be in memory</h2>
 *
 * <p>On a real release the two race, and all four orders have to work:
 *
 * <ul>
 *   <li>an {@code SCMRelease}-triggered run goes green — both facts are one row, and it announces at
 *       green exactly as it always did. That path takes no lookup at all: the event that caused the
 *       run IS the release announcement, so the join is closed by construction. It is also what keeps
 *       the manual trigger door working, since a hand-supplied {@code SCMRelease} rides no bus and
 *       leaves no fact row;
 *   <li>a tag-triggered run goes green with the release already recorded — announced at green;
 *   <li>a tag-triggered run goes green first — the announcement is <b>owed</b>, and the {@code
 *       SCMRelease} makes it when it arrives;
 *   <li>a tag-triggered run goes green and no {@code SCMRelease} ever comes — nothing is announced,
 *       ever. <b>No timeout and no fallback</b>: a replay has no novelty to announce, and a deadline
 *       that eventually announced anyway would be the defect with a delay in front of it.
 * </ul>
 *
 * <p>Both halves are therefore rows rather than state: {@link CiReleaseAnnouncement} for what a run
 * owes and {@link CiScmRelease} for what was really released. A restart between the two costs
 * nothing, which is this platform's standing rule that announcements must not be lost.
 *
 * <h2>At-least-once, deliberately</h2>
 *
 * <p>An announcement is <b>published first and marked after</b>, inside one transaction that holds
 * the owed rows locked. So two drivers of the same key cannot both announce (the loser re-reads and
 * finds nothing owed), and a crash between the publish and the commit leaves the row owed — the boot
 * sweep announces it again. Losing an announcement is the failure this class exists to prevent;
 * making one twice is a nuisance the other way round, and that is the trade taken.
 *
 * <h2>What this class is NOT, and the deploy that looks like it is</h2>
 *
 * <p><b>Nothing here can announce before the run that published.</b> An owed row is written by a
 * green run and by nothing else, and a run owes rows only for what its own trigger file's {@code
 * artifacts:} declared — {@code CiRunService.announceRelease} returns on a null declaration before
 * this class is reached at all. So a release request's QA pipeline, which declares nothing, cannot
 * satisfy a join for a version it never built, however green and however recent it is; and an {@code
 * SCMRelease} arriving at tag time announces exactly the rows that already exist, which for a repo
 * whose release pipeline is still QUEUED is none. {@code ReleaseJoinTest} pins both directions.
 *
 * <p>That is worth stating because the symptom points here and the cause is elsewhere. A deployment
 * of {@code qits/<app>:<version>} appearing minutes after the tag — failing {@code IMAGE_MISSING}
 * because the release pipeline has not pushed yet — is qits-deployments' <b>manual door</b> ({@code
 * POST /platform-deployments/api/events/software-released}), knocked by an operator or a script
 * before the image run finished. It is distinguishable in the deployment REQUEST row: the bus door
 * records the {@code packageName} the release announced, the manual door records none. And it is not
 * free — the manual door writes a request row, {@code ReleaseTips} takes the newest request row as
 * its floor, and the genuine {@code SoftwareRelease} arriving later is then refused as "not the
 * newest release of this application any more". So the early knock does not merely fail; it can cost
 * the deployment that would have worked.
 */
@ApplicationScoped
public class ReleaseJoin {

  private static final Logger LOG = Logger.getLogger(ReleaseJoin.class);

  /**
   * The event that means a release really happened, by the name it rides the bus under.
   *
   * <p><b>A string, like {@code CiRunService.TAG_EVENT_NAME}</b>, and for the same two reasons: this
   * module has no compile-time knowledge of another context, and the vocabulary jar that owns the
   * record ({@code qits-workspaces-events}) is not on this repository's classpath in either module —
   * so unlike the tag event there is no contract test to resolve it against. A rename in
   * qits-workspaces would therefore stop the join closing rather than fail a build here, which is
   * why it is spelled once, here, and read from this constant everywhere.
   */
  public static final String RELEASE_EVENT_NAME = "SCMRelease";

  @Inject CiReleaseAnnouncementRepository announcements;
  @Inject CiScmReleaseRepository releases;

  /** The published-artifact port (see {@link ReleaseAnnouncer}); zero implementations is fine. */
  @Inject Instance<ReleaseAnnouncer> releaseAnnouncers;

  /**
   * One green release pipeline's whole claim: the run, the key it published under, and the artifacts
   * its trigger file declared.
   *
   * <p>{@code triggerEventName} is here rather than a boolean, because deciding what counts as a
   * release is this class's job and not its caller's.
   *
   * <p>{@code repoName} is the run's own public name, null on a run whose push was id-addressed. It
   * is the <b>preferred</b> half of the join key: after the identity cutover a run's {@code repoId}
   * is an opaque storage UUID while {@code SCMRelease} speaks the platform's public name, so a join
   * that only compared ids would silently never close. The id arm stays as the fallback, and it is
   * what a pre-cutover platform — where the two agree — closes on. It is <b>also</b> carried onto the
   * owed row, for the reason {@code projectId} is: the two together are the address a deploy consumer
   * reads the released repository's spec at.
   *
   * <p>{@code projectId} is carried for the announcement and for nothing else: it is no part of the
   * join key and no part of any lookup here. It travels because the announcement it ends up in may
   * be made long after this call — by a later {@code SCMRelease}, or by a boot sweep in another
   * process — and neither of those can read the run row back. Null on an id-addressed run.
   */
  public record Published(
      String runId,
      String repoId,
      String repoName,
      String projectId,
      String version,
      String triggerEventName,
      String triggerEventId,
      Instant finishedAt,
      List<CiArtifact> artifacts) {}

  /**
   * A green release pipeline finished: record what it owes, and announce it at once when the release
   * fact is already in.
   *
   * <p>Called on the run worker, after the terminal row is committed. The owed rows are written
   * whichever way the gate goes — they are the durable half of the join, not a consolation prize —
   * and a run that is held says so once, at INFO, naming the key it is waiting on.
   */
  public void onGreenReleaseRun(Published run) {
    QuarkusTransaction.requiringNew().run(() -> owe(run));
    if (releasedAlready(run)) {
      announceOwed(run.repoId(), run.version());
      return;
    }
    LOG.infof(
        "Run %s published %d artifact(s) of %s %s, but no %s for it has arrived — holding the"
            + " announcement (a bootstrap replay never announces)",
        run.runId(),
        run.artifacts().size(),
        run.repoId(),
        run.version(),
        RELEASE_EVENT_NAME);
  }

  /**
   * Whether the release half of the join is in for this run.
   *
   * <p>The run's own trigger settles it without a read when that trigger IS the release — see the
   * class javadoc on why that path must stay lookup-free.
   */
  private boolean releasedAlready(Published run) {
    if (RELEASE_EVENT_NAME.equals(run.triggerEventName())) {
      return true;
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> releases.released(run.repoId(), run.repoName(), run.version()));
  }

  /**
   * Writes one owed row per declared artifact, skipping what this run already owes or has already
   * announced.
   *
   * <p>The pre-check keeps a re-entry — a restarted event run, a boot sweep racing a live drive —
   * from turning an expected outcome into a caught constraint violation in the log. The unique
   * constraint underneath is still the guarantee; this is only what stops it being reached.
   */
  private void owe(Published run) {
    Set<String> already = new HashSet<>();
    for (CiReleaseAnnouncement existing : announcements.listForRun(run.runId())) {
      already.add(artifactKey(existing.packageType, existing.packageName));
    }
    Instant now = Instant.now();
    List<CiArtifact> artifacts = run.artifacts();
    for (int index = 0; index < artifacts.size(); index++) {
      CiArtifact artifact = artifacts.get(index);
      String type = artifact.type().declared();
      if (!already.add(artifactKey(type, artifact.name()))) {
        continue;
      }
      CiReleaseAnnouncement owed = new CiReleaseAnnouncement();
      owed.id = UUID.randomUUID().toString();
      owed.runId = run.runId();
      owed.repoId = run.repoId();
      owed.projectId = run.projectId();
      owed.repoName = run.repoName();
      owed.version = run.version();
      owed.packageType = type;
      owed.packageName = artifact.name();
      owed.artifactIndex = index;
      owed.finishedAt = run.finishedAt();
      owed.triggerEventId = run.triggerEventId();
      owed.createdAt = now;
      announcements.persist(owed);
    }
  }

  private static String artifactKey(String packageType, String packageName) {
    return packageType + " " + packageName;
  }

  /**
   * An {@code SCMRelease} arrived: record the fact, then make whatever announcements it unblocks.
   *
   * <p>Called from the bus listener while the claiming transaction stands, and it writes in a
   * transaction of its <b>own</b> — {@code CiRunService.acceptPostReceive}'s arrangement, and for the
   * same reason it has one: the claim lives on the eventstream datasource and this row on ci's, and
   * one JTA transaction does not take both. So the two do not commit together, and a claim that
   * rolled back after this returned leaves a recorded release the funnel offers again — which is
   * harmless, because the read below makes a second recording a no-op and an already-made
   * announcement is not made twice. The other direction, a lost fact behind a committed claim, cannot
   * happen: this row is written first.
   *
   * <p>Nothing here is wrapped in {@code DbRetry}, the same stance {@code acceptPostReceive} takes:
   * a retry that outlived the claim's own connection would leave a committed row behind an event the
   * funnel then re-offers.
   *
   * <p><b>Both spellings of the repository drive the announcement</b>, because an owed row is keyed
   * by the run's repository id while the event carries an id and, optionally, a registered name. The
   * lookup matches either; so must the drive.
   *
   * @param repoId the repository that released, by the id the event carries
   * @param repoName the same repository by its registered name, or null when the event carried none
   * @param version the release stamp — also the name of the tag the release push created
   * @param eventId the announcing event, kept so a row says which release made the claim
   * @param occurredAt when the release happened
   */
  public void onScmRelease(
      String repoId, String repoName, String version, String eventId, Instant occurredAt) {
    QuarkusTransaction.requiringNew()
        .run(() -> recordRelease(repoId, repoName, version, eventId, occurredAt));
    announceOwed(repoId, version);
    if (repoName != null && !repoName.isBlank() && !repoName.equals(repoId)) {
      announceOwed(repoName, version);
    }
  }

  /** The fact row, written once. The read is the guard; the unique constraint is the guarantee. */
  private void recordRelease(
      String repoId, String repoName, String version, String eventId, Instant occurredAt) {
    if (releases.findRelease(repoId, version).isPresent()) {
      return;
    }
    CiScmRelease release = new CiScmRelease();
    release.id = UUID.randomUUID().toString();
    release.repoId = repoId;
    release.repoName = repoName;
    release.version = version;
    release.eventId = eventId;
    release.occurredAt = occurredAt;
    release.seenAt = Instant.now();
    releases.persist(release);
  }

  /**
   * Announces everything one {@code (repository, version)} owes, in one transaction that holds those
   * rows locked — see {@link CiReleaseAnnouncementRepository#lockOwed} for what the lock is for and
   * the class javadoc for why the publish comes before the mark.
   *
   * <p>A failure of one announcer costs that announcement and not its siblings, the fan-out rule the
   * {@link ReleaseAnnouncer} port states: N declarations are N calls.
   */
  private void announceOwed(String repoId, String version) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              List<CiReleaseAnnouncement> owed = announcements.lockOwed(repoId, version);
              Instant now = Instant.now();
              for (CiReleaseAnnouncement row : owed) {
                for (ReleaseAnnouncer announcer : releaseAnnouncers) {
                  try {
                    announcer.onArtifactPublished(
                        row.runId,
                        row.repoId,
                        row.projectId,
                        row.repoName,
                        row.version,
                        row.packageType,
                        row.packageName,
                        row.finishedAt,
                        row.triggerEventId);
                  } catch (RuntimeException e) {
                    LOG.warnf(
                        e, "Announcing artifact %s of run %s failed", row.packageName, row.runId);
                  }
                }
                row.announcedAt = now;
              }
            });
  }

  /**
   * The crash window's recovery: every owed announcement whose release fact is already recorded is
   * made again at boot.
   *
   * <p>It exists for the gap between publishing an announcement and committing the mark — the price
   * of publishing first — and for the older gap between recording a release and driving what it
   * unblocks. Both are milliseconds wide and neither may cost an announcement.
   *
   * <p><b>It runs on its own thread, and that is a measured rule rather than tidiness.</b> A publish
   * is a bounded HTTP call, and a startup observer that blocks on the network is what killed a
   * qits-ci deployment once already: the container healthcheck's budget expires before the socket
   * ever binds, and cd kills a process that was only being polite to a service that was down. See
   * {@code DaemonReleaseListener.reconcileFromLog}.
   *
   * <p>No {@code @Priority}: this observer neither hands work to the run worker nor touches a
   * container, so it is ordered against neither of the boot pair.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    Thread sweeper = new Thread(this::sweepOwed, "ci-release-join-sweep");
    sweeper.setDaemon(true);
    sweeper.start();
  }

  /**
   * The sweep itself — package-private because {@link #onStart} skips test mode, so this is what a
   * suite drives to make a claim about a restart.
   *
   * <p>One lookup per distinct key rather than per row: N artifacts of one run are one question about
   * one release. A key with no release recorded is left exactly as it is, which is the replay case
   * and is not a failure.
   */
  void sweepOwed() {
    List<Object[]> keys;
    try {
      keys = QuarkusTransaction.requiringNew().call(announcements::distinctOwedKeys);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not read the owed release announcements at startup");
      return;
    }
    int announced = 0;
    for (Object[] key : keys) {
      String repoId = (String) key[0];
      String version = (String) key[1];
      try {
        if (QuarkusTransaction.requiringNew().call(() -> releases.released(repoId, version))) {
          announceOwed(repoId, version);
          announced++;
        }
      } catch (RuntimeException e) {
        // One key's failure must not cost the others theirs — the same containment the announcement
        // fan-out has.
        LOG.warnf(e, "Could not announce what %s %s owes", repoId, version);
      }
    }
    if (!keys.isEmpty()) {
      LOG.infof(
          "Release join swept at startup: %d of %d owed release(s) had an %s and were announced",
          announced, keys.size(), RELEASE_EVENT_NAME);
    }
  }
}
