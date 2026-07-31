package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.persistence.CiRunRepository;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The shipped {@link CiCandidateRepos}: every repository qits-ci has already heard of — the union of
 * the repo ids on its recorded runs and the bare caches under {@code <data-dir>/repos/}.
 *
 * <p>Why this rather than an enumeration of the git host is argued in {@link CiCandidateRepos}; the
 * short of it is that qits-artifacts deliberately exposes none. <b>The consequence a deployment must
 * know</b> is that a repository qits-ci has never seen a push from cannot event-trigger until it
 * pushes once. Committing the trigger file <em>is</em> such a push, so in practice the gap closes
 * itself the moment a repository opts in — but a repository whose trigger file was committed while
 * qits-ci was down, and which then never pushes again, stays invisible until it does.
 *
 * <p>The two sources are unioned rather than one preferred, because they age in opposite directions:
 * the caches hold whatever ci has ever fetched (surviving a database wipe), the run rows hold
 * whatever ci has ever recorded (surviving a data-dir wipe). Neither alone is the honest answer to
 * "which repositories does this instance know about".
 *
 * <p>{@code @DefaultBean} so that a deployment — or a test — can replace the source with a single
 * alternative bean and nothing else.
 */
@ApplicationScoped
@DefaultBean
public class KnownCiRepos implements CiCandidateRepos {

  private static final Logger LOG = Logger.getLogger(KnownCiRepos.class);

  private static final String BARE_SUFFIX = ".git";

  @Inject CiRunRepository runs;

  @ConfigProperty(name = "qits.ci.data-dir")
  String dataDir;

  /**
   * The union of both sources. The query is bracketed in its own transaction because the caller is
   * {@code ci-trigger-worker} — a worker thread has no request context, so an unwrapped read has no
   * session at all, exactly as everywhere else in this module.
   */
  @Override
  public Set<String> candidates() {
    Set<String> ids =
        new TreeSet<>(QuarkusTransaction.requiringNew().call(() -> runs.distinctRepoIds()));
    ids.addAll(cachedRepoIds());
    return ids;
  }

  /**
   * The bare caches on disk. A directory whose name is not a valid repo id is ignored rather than
   * reported: the data dir is ci's own, but the ids in it reach a git argv, so what counts as one is
   * decided by {@link CiIdentifiers} here exactly as it is at the intake.
   */
  private Set<String> cachedRepoIds() {
    Path repos = Path.of(dataDir, "repos");
    if (!Files.isDirectory(repos)) {
      return Set.of();
    }
    Set<String> ids = new TreeSet<>();
    try (Stream<Path> entries = Files.list(repos)) {
      entries
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.endsWith(BARE_SUFFIX))
          .map(name -> name.substring(0, name.length() - BARE_SUFFIX.length()))
          .filter(KnownCiRepos::isRepoId)
          .forEach(ids::add);
    } catch (Exception e) {
      LOG.warnf(e, "Could not list ci's bare caches under %s", repos);
    }
    return ids;
  }

  private static boolean isRepoId(String name) {
    try {
      CiIdentifiers.requireRepoId(name);
      return true;
    } catch (RuntimeException notAnId) {
      return false;
    }
  }
}
