package eu.wohlben.qits.ci.projects;

import eu.wohlben.qits.ci.control.CiRepoRef;
import eu.wohlben.qits.ci.control.CiRepositoryListing;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The platform's repository catalogue for the service suite, replacing {@link
 * HttpProjectsRepoListing} so that no test depends on a qits-projects being reachable — the same
 * arrangement {@code FakeGitHostRepoListing} has for the git host's listing, and duplicated from the
 * ci module's fake of this port for the reason both {@code FakeCiStepRunner}s are: the two modules
 * do not share a test classpath.
 *
 * <p><b>Unconfigured is the default and every existing test keeps its behaviour.</b> {@code
 * configured()} false is what {@code qits.ci.projects-url} being unset produces, which is what this
 * suite has always run on: the candidate set then comes off the git host's listing exactly as
 * before. A test that wants the catalogue calls {@link #set}, which configures it and seeds it in
 * one move — a configured listing with nothing in it means something else entirely (the platform
 * holds no repositories) and has its own spelling.
 *
 * <p>The real implementation's own behaviour — the timeouts, the parsing, the WARN-and-empty on a
 * failed read — is {@link HttpProjectsRepoListingTest}'s, against a real server on a real socket
 * and a directly constructed instance, so this mock does not stand in its way.
 */
@Mock
@ApplicationScoped
public class FakeProjectsRepoListing implements CiRepositoryListing {

  // Read through methods: a field read on an injected CDI client proxy sees the proxy's fields.
  private final List<CiRepoRef> repositories = new ArrayList<>();
  private volatile boolean configured;

  /** Configures this listing and seeds what it answers. */
  public void set(CiRepoRef... refs) {
    configured = true;
    repositories.clear();
    repositories.addAll(List.of(refs));
  }

  /** Back to unset: no qits-projects in this deployment, so the git host's listing answers. */
  public void unset() {
    configured = false;
    repositories.clear();
  }

  @Override
  public boolean configured() {
    return configured;
  }

  @Override
  public List<CiRepoRef> repositories() {
    return List.copyOf(repositories);
  }
}
