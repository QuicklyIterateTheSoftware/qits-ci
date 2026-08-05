package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The union {@link CiCandidateRepos} the engine gets: the git host's listing added to what qits-ci
 * already knows, and the known set alone when the listing answers nothing.
 *
 * <p>A {@code @QuarkusTest} because the union's whole content is which beans it composes — {@link
 * KnownCiRepos} by its own type past its {@code @DefaultBean}, the port through an {@code Instance}
 * — and a hand-wired instance would prove none of that. The HTTP half is
 * {@code service/…/githost/HttpGitHostRepoListingTest}; that it closes the production gap end to end
 * is {@code service/…/api/CiManualTriggerTest}.
 */
@QuarkusTest
public class ListedAndKnownCiReposTest {

  @Inject ListedAndKnownCiRepos candidates;

  @Inject KnownCiRepos known;

  @Inject FakeGitHostRepoListing listing;

  @AfterEach
  void clearListing() {
    // The candidate list is shared by every evaluation in this JVM — leaving an id in it would make
    // it a candidate for every other test's events.
    listing.set();
  }

  @Test
  public void aListingThatAnswersNothingLeavesTheKnownSetExactlyAsItWas() {
    listing.set();

    assertEquals(known.candidates(), candidates.candidates());
  }

  @Test
  public void aRepositoryOnlyTheGitHostListsIsACandidate() {
    String onlyListed = "listed-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    listing.set(onlyListed);

    Set<String> all = candidates.candidates();
    assertTrue(all.contains(onlyListed), all.toString());
    assertTrue(all.containsAll(known.candidates()), "union, not replacement: " + all);
  }
}
