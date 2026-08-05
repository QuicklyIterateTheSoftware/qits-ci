package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The git host's listing for the ci suite: whatever the test says, and no HTTP. The production
 * implementation is {@code service/…/githost/HttpGitHostRepoListing} — this module has none, which
 * is the configuration {@link GitHostRepoListing} documents as supported, so this fake is also what
 * makes the port resolvable here at all.
 *
 * <p>Empty is the default and it is the interesting one: an empty listing is exactly what an
 * unreachable git host answers, so {@link ListedAndKnownCiReposTest}'s fallback case needs no
 * failure to stage.
 */
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
