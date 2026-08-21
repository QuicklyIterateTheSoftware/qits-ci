package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The union {@link CiCandidateRepos} the engine gets: the platform's catalogue added to what qits-ci
 * already knows, and the known set alone when neither listing answers anything.
 *
 * <p>It also pins <b>which</b> catalogue answers. qits-projects is preferred because it is the only
 * one that can say a repository's public name; the git host's storage listing is the fallback for a
 * deployment that names no qits-projects, which is the campaign's kill switch and what keeps a
 * pre-cutover platform triggering.
 *
 * <p>A {@code @QuarkusTest} because the union's whole content is which beans it composes — {@link
 * KnownCiRepos} by its own type past its {@code @DefaultBean}, both ports through an {@code
 * Instance} — and a hand-wired instance would prove none of that. The HTTP halves are {@code
 * service/…/githost/HttpGitHostRepoListingTest} and {@code
 * service/…/projects/HttpProjectsRepoListingTest}; that it closes the production gap end to end is
 * {@code service/…/api/CiManualTriggerTest}.
 */
@QuarkusTest
public class ListedAndKnownCiReposTest {

  @Inject ListedAndKnownCiRepos candidates;

  @Inject KnownCiRepos known;

  @Inject FakeGitHostRepoListing listing;

  @Inject FakeProjectsRepoListing projects;

  @AfterEach
  void clearListings() {
    // The candidate list is shared by every evaluation in this JVM — leaving an entry in it would
    // make that repository a candidate for every other test's events.
    listing.set();
    projects.unset();
  }

  private static String someId(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private List<String> candidateIds() {
    return candidates.candidates().stream().map(CiRepoRef::repoId).toList();
  }

  @Test
  public void aListingThatAnswersNothingLeavesTheKnownSetExactlyAsItWas() {
    listing.set();

    assertEquals(known.candidates(), candidates.candidates());
  }

  @Test
  public void aRepositoryOnlyTheGitHostListsIsACandidate() {
    String onlyListed = someId("listed");
    listing.set(onlyListed);

    List<String> all = candidateIds();
    assertTrue(all.contains(onlyListed), all.toString());
    assertTrue(
        all.containsAll(known.candidates().stream().map(CiRepoRef::repoId).toList()),
        "union, not replacement: " + all);
  }

  @Test
  public void theProjectsCatalogueAnswersWithNamesAndTheGitHostListingStandsDown() {
    // The cutover arm: qits-projects is configured, so its entries — which carry the public
    // coordinate — are the catalogue, and the git host's storage ids do not join them.
    String uuid = someId("uuid");
    String stale = someId("stale");
    listing.set(stale);
    projects.set(new CiRepoRef(uuid, "qits", "qits-blobstore"));

    List<CiRepoRef> all = candidates.candidates();
    CiRepoRef named =
        all.stream().filter(ref -> ref.repoId().equals(uuid)).findFirst().orElseThrow();
    assertEquals("qits", named.projectId());
    assertEquals("qits-blobstore", named.name());
    assertTrue(named.named());
    assertFalse(
        all.stream().anyMatch(ref -> ref.repoId().equals(stale)),
        "a configured catalogue is the authority; the storage listing does not add to it");
  }

  @Test
  public void anUnsetProjectsUrlFallsBackToTheGitHostListing() {
    // The kill switch, which is also every pre-cutover deployment: no qits-projects configured, so
    // the git host's own listing is what the engine evaluates — exactly as it did before names.
    String onlyListed = someId("fallback");
    projects.unset();
    listing.set(onlyListed);

    CiRepoRef found =
        candidates.candidates().stream()
            .filter(ref -> ref.repoId().equals(onlyListed))
            .findFirst()
            .orElseThrow();
    assertFalse(found.named(), "the storage listing knows no names: " + found);
  }

  @Test
  public void aCatalogueThatCouldNotBeReadNeverShrinksTheCandidateSet() {
    // Configured and empty is what an unreachable qits-projects answers. The known set must survive
    // it whole — a read failure must never shrink the candidate set.
    projects.configuredButEmpty();

    assertEquals(known.candidates(), candidates.candidates());
  }
}
