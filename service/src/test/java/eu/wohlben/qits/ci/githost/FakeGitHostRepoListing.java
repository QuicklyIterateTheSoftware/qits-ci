package eu.wohlben.qits.ci.githost;

import eu.wohlben.qits.ci.control.GitHostRepoListing;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The git host's listing for the service suite, replacing {@link HttpGitHostRepoListing} so no test
 * depends on a listing endpoint existing. <b>Empty by default</b>, which is exactly what an
 * unreachable git host answers, so the fallback needs no failure to stage — and it keeps a
 * repository a test seeded on {@link StubGitHost} out of every other test's candidate set until the
 * test that wants it says so.
 *
 * <p>Duplicated from the ci module's fake of the same port for the reason both {@code
 * FakeCiStepRunner}s are: the two modules do not share a test classpath.
 *
 * <p>The real implementation's own behaviour — the timeouts, the fallbacks, the cache — is {@link
 * HttpGitHostRepoListingTest}'s, against a real server on a real socket.
 */
@Mock
@ApplicationScoped
public class FakeGitHostRepoListing implements GitHostRepoListing {

  // Read through methods: a field read on an injected CDI client proxy sees the proxy's fields.
  private final Set<String> repoIds = new LinkedHashSet<>();

  public void set(String... ids) {
    repoIds.clear();
    repoIds.addAll(List.of(ids));
  }

  @Override
  public Set<String> repositories() {
    return Set.copyOf(repoIds);
  }
}
