package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The evaluator, exhaustively. It is the function that decides whether a repository's pipeline runs
 * on somebody else's event, so it is pure, and this suite is what "pure" is for: every matcher, both
 * directions of {@code exists}, missing paths, nested paths, non-string JSON values, and the
 * AND/OR arithmetic — with no container, no git and no bus anywhere in it.
 *
 * <p>Selections are written as YAML and parsed, rather than built by hand, so that what is asserted
 * is what a repository would actually commit.
 */
public class CiEventSelectionEvaluatorTest {

  private static final String PATH = ".config/qits/ci-event-x.yml";

  private static final String PAYLOAD =
      """
      {"branch":"main",
       "commitSha":"cafebabe",
       "repoId":"qits-spa-ui-components",
       "count":3,
       "green":true,
       "tags":["a","b"],
       "repository":{"url":"http://qits-artifacts:8080/artifacts/git/x","nested":{"deep":"yes"}}}
      """;

  private final CiEventTriggerParser parser = new CiEventTriggerParser();

  private boolean matches(String when) {
    return CiEventSelectionEvaluator.matches(selection(when), payload());
  }

  private CiEventSelection selection(String when) {
    return parser.parse(PATH, "event: E\n" + when).selection();
  }

  private static JsonNode payload() {
    return CiEventSelectionEvaluator.parsePayload(PAYLOAD);
  }

  // --- unconditional ---

  @Test
  public void anUnconditionalSelectionMatchesEverything() {
    assertTrue(CiEventSelectionEvaluator.matches(CiEventSelection.unconditional(), payload()));
    assertTrue(CiEventSelectionEvaluator.matches(CiEventSelection.unconditional(), null));
    assertTrue(CiEventSelectionEvaluator.matches(null, payload()));
  }

  // --- exact ---

  @Test
  public void exactMatchesTheWholeValueAndNothingLess() {
    assertTrue(matches("when:\n  - repoId: { exact: qits-spa-ui-components }\n"));
    assertFalse(matches("when:\n  - repoId: { exact: qits-spa }\n"));
    assertFalse(matches("when:\n  - repoId: { exact: QITS-SPA-UI-COMPONENTS }\n"));
    assertFalse(matches("when:\n  - repoId: { exact: \"\" }\n"));
  }

  @Test
  public void exactFailsOnAMissingPath() {
    assertFalse(matches("when:\n  - nope: { exact: anything }\n"));
    assertFalse(matches("when:\n  - repository.absent: { exact: anything }\n"));
  }

  // --- prefix ---

  @Test
  public void prefixMatchesTheStartOfTheValue() {
    assertTrue(matches("when:\n  - repoId: { prefix: qits- }\n"));
    assertTrue(matches("when:\n  - repoId: { prefix: qits-spa-ui-components }\n"), "the whole value is a prefix of itself");
    assertTrue(matches("when:\n  - repoId: { prefix: \"\" }\n"), "every string starts with nothing");
    assertFalse(matches("when:\n  - repoId: { prefix: spa }\n"), "a prefix is not a substring");
    assertFalse(matches("when:\n  - repoId: { prefix: qits-spa-ui-components-x }\n"));
  }

  @Test
  public void prefixFailsOnAMissingPath() {
    assertFalse(matches("when:\n  - nope: { prefix: q }\n"));
  }

  // --- exists, both directions ---

  @Test
  public void existsTrueIsWhetherThePathResolves() {
    assertTrue(matches("when:\n  - repoId: { exists: true }\n"));
    assertTrue(matches("when:\n  - repository.nested.deep: { exists: true }\n"));
    assertFalse(matches("when:\n  - nope: { exists: true }\n"));
  }

  @Test
  public void existsFalseIsHowARepositoryAssertsAbsence() {
    assertTrue(matches("when:\n  - nope: { exists: false }\n"));
    assertTrue(matches("when:\n  - repository.nope: { exists: false }\n"));
    // The case worth writing down: exists:false on a path that IS present must fail.
    assertFalse(matches("when:\n  - repoId: { exists: false }\n"));
    assertFalse(matches("when:\n  - repository.nested.deep: { exists: false }\n"));
  }

  // --- non-string JSON values compare by their literal ---

  @Test
  public void nonStringValuesCompareByTheirJsonLiteral() {
    assertTrue(matches("when:\n  - count: { exact: \"3\" }\n"));
    assertTrue(matches("when:\n  - green: { exact: \"true\" }\n"));
    assertFalse(matches("when:\n  - green: { exact: \"false\" }\n"));
    assertTrue(matches("when:\n  - count: { prefix: \"3\" }\n"));
    assertTrue(matches("when:\n  - count: { exists: true }\n"));
  }

  @Test
  public void anArrayAndAnObjectAreTheirOwnLiteralsAndAreNotNavigableFurther() {
    assertTrue(matches("when:\n  - tags: { exact: \"[\\\"a\\\",\\\"b\\\"]\" }\n"));
    assertTrue(matches("when:\n  - tags: { prefix: \"[\\\"a\\\"\" }\n"));
    assertTrue(matches("when:\n  - tags: { exists: true }\n"));
    // Navigation only: an array or a scalar part-way down a path ends the walk with "not there",
    // and an INDEX is not even spellable — the parser refuses `tags.0` before evaluation sees it.
    assertTrue(matches("when:\n  - tags.a: { exists: false }\n"));
    assertTrue(matches("when:\n  - repoId.anything: { exists: false }\n"));
  }

  // --- nesting ---

  @Test
  public void dotPathsNavigateNestedObjects() {
    assertTrue(matches("when:\n  - repository.url: { prefix: \"http://\" }\n"));
    // Quoted, and the quoting is the point: bare `yes` is a YAML BOOLEAN, so `exact: yes` is a
    // non-string matcher value and a parse error. Loud rather than silently comparing "true".
    assertTrue(matches("when:\n  - repository.nested.deep: { exact: \"yes\" }\n"));
    assertFalse(matches("when:\n  - repository.nested.deep: { exact: \"no\" }\n"));
    assertTrue(matches("when:\n  - repository.nested.deeper: { exists: false }\n"));
  }

  // --- the arithmetic ---

  @Test
  public void aGroupsEntriesAreAnded() {
    assertTrue(
        matches(
            """
            when:
              - repoId: { exact: qits-spa-ui-components }
                branch: { exact: main }
            """));
    assertFalse(
        matches(
            """
            when:
              - repoId: { exact: qits-spa-ui-components }
                branch: { exact: release }
            """),
        "one failing entry fails the group");
  }

  @Test
  public void groupsAreOred() {
    assertTrue(
        matches(
            """
            when:
              - repoId: { exact: something-else }
              - repoId: { exact: qits-spa-ui-components }
            """),
        "the second group matching is enough");
    assertFalse(
        matches(
            """
            when:
              - repoId: { exact: something-else }
              - repoId: { exact: another-thing }
            """));
  }

  @Test
  public void matchersOnOnePathAreAnded() {
    assertTrue(
        matches(
            """
            when:
              - repoId:
                  - { prefix: qits- }
                  - { exact: qits-spa-ui-components }
            """));
    assertFalse(
        matches(
            """
            when:
              - repoId:
                  - { prefix: qits- }
                  - { exact: qits-something-else }
            """));
    assertFalse(
        matches("when:\n  - repoId:\n      - { prefix: qits- }\n      - { exists: false }\n"),
        "exists:false and a prefix on one present path can never both hold");
  }

  @Test
  public void theWholeThingTogether() {
    // Two groups, one with three AND'd entries including a matcher list and an absence assertion.
    assertTrue(
        matches(
            """
            when:
              - repoId: { exact: never }
              - repoId:
                  - { prefix: qits-spa }
                  - { exists: true }
                repository.url: { prefix: "http://qits-artifacts" }
                imageDigest: { exists: false }
            """));
  }

  // --- payloads that are not payloads ---

  @Test
  public void anAbsentOrUnreadablePayloadHasNoPathsInIt() {
    assertNull(CiEventSelectionEvaluator.parsePayload(null));
    assertNull(CiEventSelectionEvaluator.parsePayload("   "));
    assertNull(CiEventSelectionEvaluator.parsePayload("not json at all {"));

    CiEventSelection wantsAValue = selection("when:\n  - repoId: { exact: x }\n");
    assertFalse(CiEventSelectionEvaluator.matches(wantsAValue, null));
    CiEventSelection wantsAbsence = selection("when:\n  - repoId: { exists: false }\n");
    assertTrue(
        CiEventSelectionEvaluator.matches(wantsAbsence, null),
        "nothing to look in is still an honest absence");
  }

  @Test
  public void aJsonNullCountsAsAbsent() {
    // Unreachable through this platform's canonical form, which omits null fields entirely — stated
    // rather than left to Jackson, because "the field is there and its value is nothing" is not a
    // distinction a selection should have to make.
    JsonNode withNull = CiEventSelectionEvaluator.parsePayload("{\"a\":null}");
    assertTrue(CiEventSelectionEvaluator.matches(selection("when:\n  - a: { exists: false }\n"), withNull));
    assertFalse(CiEventSelectionEvaluator.matches(selection("when:\n  - a: { exists: true }\n"), withNull));
    assertFalse(CiEventSelectionEvaluator.matches(selection("when:\n  - a: { exact: \"null\" }\n"), withNull));
  }

  // --- repoId names the ADDRESSABLE NAME, with the id as the legacy arm ---

  /**
   * The estate's nine {@code ci-event-release.yml} files, unedited, against the post-cutover event:
   * the payload's {@code repoId} is an opaque storage UUID and the file names the repository the way
   * a person does, so the match has to come off {@code repoName}.
   */
  @Test
  public void repoIdMatchesTheRepoNameWhenTheEventCarriesOne() {
    JsonNode nameAddressed =
        CiEventSelectionEvaluator.parsePayload(
            "{\"repoId\":\"2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f\","
                + "\"projectId\":\"qits\",\"repoName\":\"qits-blobstore\"}");

    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repoId: { exact: qits-blobstore }\n"), nameAddressed));
    assertFalse(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repoId: { exact: 2f1c9b3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f }\n"),
            nameAddressed),
        "the storage id is not something a trigger file may select on once a name is there");
  }

  /**
   * The compatibility arm, which is every event published before the cutover and every push on the
   * internal id-addressed route: no {@code repoName}, so the id is what the matcher reads — which is
   * why the same nine files keep working on both sides of the cutover.
   */
  @Test
  public void repoIdFallsBackToTheIdWhenTheEventCarriesNoName() {
    assertTrue(matches("when:\n  - repoId: { exact: qits-spa-ui-components }\n"));
    assertTrue(matches("when:\n  - repoId: { prefix: qits-spa }\n"));
    assertTrue(matches("when:\n  - repoId: { exists: true }\n"));
  }

  // --- and so does `repository`, which is how qits-workspaces spells the same thing ---

  /**
   * The live defect of 2026-08-22, in one case. An {@code SCMRelease} out of qits-workspaces carries
   * {@code repository} = the storage UUID and {@code repositoryName} = the name, and every
   * repository's {@code ci-event-release.yml} selects the name. Without this alias {@code
   * repository} resolved literally to the UUID, nothing matched, and no release pipeline ran at all
   * after the re-bootstrap.
   */
  @Test
  public void repositoryMatchesTheRepositoryNameWhenTheEventCarriesOne() {
    JsonNode release =
        CiEventSelectionEvaluator.parsePayload(
            "{\"branch\":\"release/maintenance-service\","
                + "\"projectId\":\"b03b7c4e-1d2f-4a5b-8c9d-0e1f2a3b4c5d\","
                + "\"repository\":\"764e8bf9-3a2b-4c1d-9e8f-7a6b5c4d3e2f\","
                + "\"repositoryName\":\"qits-platform-maintenance\","
                + "\"version\":\"2026.822.170613\"}");

    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repository: { exact: qits-platform-maintenance }\n"), release));
    assertFalse(
        CiEventSelectionEvaluator.matches(
            selection(
                "when:\n  - repository: { exact: 764e8bf9-3a2b-4c1d-9e8f-7a6b5c4d3e2f }\n"),
            release),
        "the storage id is not something a trigger file may select on once a name is there");
  }

  /** The compatibility arm: no {@code repositoryName}, so {@code repository} reads itself. */
  @Test
  public void repositoryFallsBackToItselfWhenTheEventCarriesNoName() {
    JsonNode preCutover =
        CiEventSelectionEvaluator.parsePayload(
            "{\"repository\":\"qits-blobstore\",\"version\":\"2026.811.1\"}");

    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repository: { exact: qits-blobstore }\n"), preCutover));
    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repository: { prefix: qits- }\n"), preCutover));
    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repository: { exists: true }\n"), preCutover));
  }

  /**
   * The alias must not cost the ordinary reading of the word. This suite's own base payload carries
   * an OBJECT at {@code repository} and no {@code repositoryName}, so it falls through to the
   * literal — and every nested path below it was never a candidate for the alias in the first place.
   */
  @Test
  public void aRepositoryThatIsAnObjectIsStillWalkedIntoLiterally() {
    assertTrue(matches("when:\n  - repository.url: { prefix: \"http://\" }\n"));
    assertTrue(matches("when:\n  - repository.nested.deep: { exact: \"yes\" }\n"));
    assertTrue(matches("when:\n  - repository: { exists: true }\n"));
  }

  /** The alias is those top-level paths and nothing else — never a nested key of the same name. */
  @Test
  public void onlyTheTopLevelRepoIdPathIsAliased() {
    JsonNode nested =
        CiEventSelectionEvaluator.parsePayload(
            "{\"repoName\":\"the-name\",\"repository\":{\"repoId\":\"inner\"}}");

    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repository.repoId: { exact: inner }\n"), nested));
    assertFalse(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - repository.repoId: { exact: the-name }\n"), nested));
  }

  /** The same restriction for the second alias, stated on its own so neither can be widened alone. */
  @Test
  public void onlyTheTopLevelRepositoryPathIsAliased() {
    JsonNode nested =
        CiEventSelectionEvaluator.parsePayload(
            "{\"repositoryName\":\"the-name\",\"outer\":{\"repository\":\"inner\"}}");

    assertTrue(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - outer.repository: { exact: inner }\n"), nested));
    assertFalse(
        CiEventSelectionEvaluator.matches(
            selection("when:\n  - outer.repository: { exact: the-name }\n"), nested));
  }

  @Test
  public void aScalarOrArrayDocumentRootResolvesNothing() {
    JsonNode scalar = CiEventSelectionEvaluator.parsePayload("\"just a string\"");
    assertNull(CiEventSelectionEvaluator.resolve(scalar, "a"));
    JsonNode array = CiEventSelectionEvaluator.parsePayload("[1,2]");
    assertNull(CiEventSelectionEvaluator.resolve(array, "a"));
  }
}
