package eu.wohlben.qits.ci.control;

import eu.wohlben.qits.ci.persistence.CiRunRepository;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.arc.DefaultBean;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Every repository qits-ci has already heard of — the repo ids on its recorded runs.
 *
 * <p>It was the whole of {@link CiCandidateRepos} once. It is now <b>one half</b> of {@link
 * ListedAndKnownCiRepos}, which adds the git host's own listing on top — and it is the half that
 * still answers when that listing cannot be read, which is why it stays a {@link CiCandidateRepos}
 * in its own right rather than becoming a helper. Read {@link CiCandidateRepos} for the argument.
 *
 * <p>It used to union the run rows with the per-repository bare caches on disk. Those caches are
 * gone — the config is read off the git host's content endpoints now, and qits-ci mirrors nothing —
 * so the run rows are the whole of what this instance knows on its own, and the git host's listing
 * is what covers a repository it has never built.
 *
 * <p>{@code @DefaultBean} so that {@link ListedAndKnownCiRepos} — an ordinary bean — is the one the
 * engine's {@code CiCandidateRepos} injection point resolves to, while this stays injectable by its
 * own type. A {@code @Mock} alternative outranks both and replaces the seam whole, which is what the
 * ci suite's {@code FakeCandidateRepos} does.
 */
@ApplicationScoped
@DefaultBean
public class KnownCiRepos implements CiCandidateRepos {

  @Inject CiRunRepository runs;

  /**
   * How long this read holds while the datasource is gone. The shipped 15s covers a postgres
   * cutover; the suite shortens it, because a test proving the give-up must not pay for it.
   */
  @ConfigProperty(name = "qits.ci.db-retry-deadline", defaultValue = "15S")
  Duration dbRetryDeadline;

  /**
   * The query is bracketed in its own transaction because the caller is {@code ci-trigger-worker} —
   * a worker thread has no request context, so an unwrapped read has no session at all, exactly as
   * everywhere else in this module.
   *
   * <p><b>And the bracket is wrapped in {@link DbRetry}, from OUTSIDE.</b> A connection lost
   * mid-flight — after the statement ran, which is where the pool's patient driver cannot help —
   * would otherwise shrink the candidate set to the git host's listing alone for that evaluation,
   * and a read failure must never shrink the candidate set (see {@link ListedAndKnownCiRepos}).
   * Outside rather than inside, so every attempt runs in a fresh transaction on a freshly borrowed
   * connection; inside it would re-run the query in a transaction the severed connection already
   * doomed.
   */
  @Override
  public Set<String> candidates() {
    return new TreeSet<>(
        DbRetry.call(
            "known ci repository listing",
            () -> QuarkusTransaction.requiringNew().call(() -> runs.distinctRepoIds()),
            retryDeadline()));
  }

  /**
   * The configured deadline, or the library's default for an instance nobody injected — which is
   * how a hand-built test subclass arrives, constructed with {@code new} and calling {@code super}
   * for the part it does not fake.
   */
  private Duration retryDeadline() {
    return dbRetryDeadline == null ? DbRetry.DEFAULT_DEADLINE : dbRetryDeadline;
  }
}
