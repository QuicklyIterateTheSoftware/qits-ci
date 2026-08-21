package eu.wohlben.qits.ci.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * The platform's repository catalogue for the ci suite: whatever the test says, and no HTTP. The
 * production implementation is {@code service/…/projects/HttpProjectsRepoListing} — this module has
 * none, which is the configuration {@link CiRepositoryListing} documents as supported, so this fake
 * is also what makes the port resolvable here at all.
 *
 * <p><b>Unconfigured is the default, and it is the interesting one</b>: it is the arm every
 * deployment that has not moved to the catalogue is on, and the one that must still trigger off the
 * git host's own listing. A test that wants the catalogue calls {@link #set} — which configures it
 * and seeds it in one move, because a configured listing with nothing in it means something else
 * entirely (the platform holds no repositories).
 */
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

  /** Configured but unreadable — the WARN-and-empty arm of the production client. */
  public void configuredButEmpty() {
    configured = true;
    repositories.clear();
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
