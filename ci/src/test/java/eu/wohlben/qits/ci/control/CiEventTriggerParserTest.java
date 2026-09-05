package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiEventSelection.Group;
import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
import eu.wohlben.qits.ci.control.CiEventSelection.PathCondition;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The trigger-file parser is pure — plain JUnit, no Quarkus. */
public class CiEventTriggerParserTest {

  private static final String PATH = ".config/qits/ci-event-ui-components-released.yml";

  private final CiEventTriggerParser parser = new CiEventTriggerParser();

  @Test
  public void parsesTheReleaseTrainShape() {
    CiEventTrigger trigger =
        parser.parse(
            PATH,
            """
            event: BuildSuccessful
            when:
              - repoId: { exact: qits-spa-ui-components }
                branch: { exact: main }
            steps:
              - image: qits/build-images/node-base:latest
                script: ./bump.sh
            """);

    assertEquals(PATH, trigger.configPath());
    assertEquals("BuildSuccessful", trigger.eventName());
    assertEquals(1, trigger.pipeline().steps().size());
    assertEquals("./bump.sh", trigger.pipeline().steps().get(0).script());

    assertEquals(1, trigger.selection().groups().size());
    Group group = trigger.selection().groups().get(0);
    assertEquals(2, group.conditions().size(), "a group's map entries are AND'd");
    assertTrue(trigger.gating(), "absent gating is true — the conservative default");
  }

  // --- gating ---

  @Test
  public void gatingFalseParsesAndAnythingElseIsAParseError() {
    // The userflow pipelines' key: a red story must not stand in the way of releasing the commit.
    CiEventTrigger nonGating =
        parser.parse(
            PATH,
            """
            event: BuildSuccessful
            gating: false
            steps: []
            """);
    assertFalse(nonGating.gating());

    // Strict on this file's standing reason: a gating flag that silently parsed to a default would
    // decide who may release with nobody having said so.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: BuildSuccessful\ngating: nope\nsteps: []\n"));
    assertTrue(e.getMessage().contains("gating"), e.getMessage());
  }

  // --- the two-way rule ---

  @Test
  public void aTriggerFileWithoutAnEventIsAParseError() {
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "steps:\n  - image: alpine:3\n    script: \"true\"\n"));
    assertTrue(e.getMessage().contains("event"), e.getMessage());
  }

  @Test
  public void anEmptyTriggerFileIsAParseError() {
    // Unlike ci-post-receive.yml, where an empty file is a visible opt-in with no steps: a trigger
    // that names no event is not a trigger at all.
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, ""));
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "# only a comment\n"));
  }

  @Test
  public void aPostReceiveFileDeclaringEventOrWhenIsAParseError() {
    // The other half of the same rule, asserted here so the pair reads in one place.
    CiConfigParser postReceive = new CiConfigParser();
    assertThrows(
        CiConfigException.class,
        () -> postReceive.parse("event: BuildSuccessful\nsteps: []\n"));
    assertThrows(
        CiConfigException.class,
        () -> postReceive.parse("when:\n  - repoId: { exact: x }\nsteps: []\n"));
  }

  @Test
  public void aStepDeclaringBranchesIsAParseErrorNamingTheFile() {
    // Allow-but-inert is the trap this refuses. An event-triggered run always builds the head of
    // main, so `exact: main` here would be decoration and anything else a step that is ALWAYS
    // skipped — indistinguishable at a glance from one that never got its turn. The same asymmetry
    // argument the two-way rule makes at the top level, one level down.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () ->
                parser.parse(
                    PATH,
                    """
                    event: SoftwareRelease
                    steps:
                      - image: alpine:3
                        script: "true"
                        branches:
                          - prefix: maintenance/
                    """));
    assertTrue(e.getMessage().contains(PATH), e.getMessage());
    assertTrue(e.getMessage().contains("branches"), e.getMessage());
    // Even the inert spelling: it is refused for what it cannot mean, not for what it happens to say.
    assertThrows(
        CiConfigException.class,
        () ->
            parser.parse(
                PATH,
                "event: SoftwareRelease\nsteps:\n  - image: alpine:3\n    script: \"true\"\n"
                    + "    branches:\n      - exact: main\n"));
  }

  @Test
  public void aBlankOrNonStringEventIsAParseError() {
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "event: \"\"\n"));
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "event: 7\n"));
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "event:\n  - a\n"));
  }

  // --- when: absent, empty, and the shapes ---

  @Test
  public void anAbsentWhenMeansUnconditional() {
    // Documented and deliberate: a repository writing only `event:` has said something complete, and
    // reading it as "matches nothing" would make the commonest trigger the one that never fires.
    CiEventTrigger trigger = parser.parse(PATH, "event: BuildSuccessful\nsteps: []\n");
    assertTrue(trigger.selection().isUnconditional());
  }

  @Test
  public void anEmptyWhenMeansUnconditionalToo() {
    assertTrue(
        parser.parse(PATH, "event: BuildSuccessful\nwhen: []\nsteps: []\n").selection()
            .isUnconditional());
  }

  @Test
  public void severalGroupsAreKeptInOrder() {
    CiEventSelection selection =
        parser
            .parse(
                PATH,
                """
                event: BuildSuccessful
                when:
                  - repoId: { exact: a }
                  - repoId: { exact: b }
                """)
            .selection();
    assertEquals(2, selection.groups().size());
    assertFalse(selection.isUnconditional());
  }

  @Test
  public void aMatcherListOnOnePathIsTheSamePathTwice() {
    // The one thing a plain map cannot spell, and the reason the list form exists.
    PathCondition condition =
        parser
            .parse(
                PATH,
                """
                event: BuildSuccessful
                when:
                  - repoId:
                      - { prefix: qits- }
                      - { exists: true }
                """)
            .selection()
            .groups()
            .get(0)
            .conditions()
            .get(0);
    assertEquals("repoId", condition.path());
    assertEquals(2, condition.matchers().size());
    assertEquals(Matcher.Kind.PREFIX, condition.matchers().get(0).kind());
    assertEquals(Matcher.Kind.EXISTS, condition.matchers().get(1).kind());
  }

  @Test
  public void severalMatcherKeysInOneMappingAreSeveralMatchers() {
    PathCondition condition =
        parser
            .parse(
                PATH,
                "event: E\nwhen:\n  - repoId: { prefix: qits-, exists: true }\n")
            .selection()
            .groups()
            .get(0)
            .conditions()
            .get(0);
    assertEquals(2, condition.matchers().size());
  }

  @Test
  public void everyMatcherKindParses() {
    CiEventSelection selection =
        parser
            .parse(
                PATH,
                """
                event: E
                when:
                  - a: { exact: one }
                    b: { prefix: two }
                    c: { exists: false }
                """)
            .selection();
    Group group = selection.groups().get(0);
    assertEquals(3, group.conditions().size());
    for (PathCondition condition : group.conditions()) {
      Matcher matcher = condition.matchers().get(0);
      switch (condition.path()) {
        case "a" -> {
          assertEquals(Matcher.Kind.EXACT, matcher.kind());
          assertEquals("one", matcher.value());
        }
        case "b" -> {
          assertEquals(Matcher.Kind.PREFIX, matcher.kind());
          assertEquals("two", matcher.value());
        }
        case "c" -> {
          assertEquals(Matcher.Kind.EXISTS, matcher.kind());
          assertFalse(matcher.expected());
        }
        default -> throw new AssertionError("unexpected path " + condition.path());
      }
    }
  }

  @Test
  public void nestedDotPathsParse() {
    assertEquals(
        "repository.url",
        parser
            .parse(PATH, "event: E\nwhen:\n  - repository.url: { prefix: \"http\" }\n")
            .selection()
            .groups()
            .get(0)
            .conditions()
            .get(0)
            .path());
  }

  // --- everything loud ---

  @Test
  public void anUnknownTopLevelKeyIsAParseError() {
    // Strict where ci-post-receive.yml is lenient, and the reason is correctness rather than taste:
    // a mistyped `wehn:` would otherwise parse as "no selection", and no selection means
    // UNCONDITIONAL — silently widening the trigger to every event of that name.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: E\nwehn:\n  - a: { exact: b }\n"));
    assertTrue(e.getMessage().contains("wehn"), e.getMessage());
    assertTrue(e.getMessage().contains(PATH), "the message must name the file");
  }

  @Test
  public void anUnknownMatcherIsAParseError() {
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: E\nwhen:\n  - repoId: { regex: \"^qits-\" }\n"));
    assertTrue(e.getMessage().contains("regex"), e.getMessage());
  }

  @Test
  public void malformedWhenStructureIsAParseError() {
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen: everything\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - just-a-string\n"));
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - {}\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - repoId: qits\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - repoId: []\n"));
  }

  @Test
  public void aNonStringMatcherValueIsAParseErrorRatherThanCoerced() {
    // `exact: 3` and `exact: "3"` would otherwise be the same declaration, and a repository
    // comparing against a JSON number should say so the way it reads it.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: E\nwhen:\n  - count: { exact: 3 }\n"));
    assertTrue(e.getMessage().contains("strings"), e.getMessage());
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: E\nwhen:\n  - flag: { prefix: true }\n"));
  }

  @Test
  public void aNonBooleanExistsIsAParseError() {
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: E\nwhen:\n  - a: { exists: \"true\" }\n"));
  }

  @Test
  public void aPathThatIsNotADotPathIsAParseError() {
    // Navigation only: no wildcards, no filters, no indexing — checked here rather than discovered
    // by a walk that quietly resolves nothing.
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - \"a.*\": { exists: true }\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - \"a[0]\": { exists: true }\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - \"$.a\": { exists: true }\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - \"a..b\": { exists: true }\n"));
    assertThrows(
        CiConfigException.class, () -> parser.parse(PATH, "event: E\nwhen:\n  - 7: { exists: true }\n"));
  }

  @Test
  public void aDuplicateKeyIsAParseErrorHereThoughNotInAPipeline() {
    // A silently dropped condition WIDENS a selection, which is the one failure this file may not
    // have. ci-post-receive.yml keeps SnakeYAML's last-one-wins, because tightening it would turn
    // config that works today into a CONFIG_ERROR run in every repository at once.
    assertThrows(
        CiConfigException.class,
        () ->
            parser.parse(
                PATH,
                "event: E\nwhen:\n  - repoId: { exact: a }\n    repoId: { exact: b }\n"));
    assertEquals(
        1,
        new CiConfigParser()
            .parse("steps:\n  - image: a\n    script: x\n    script: y\n")
            .steps()
            .size());
  }

  @Test
  public void malformedYamlIsAParseError() {
    assertThrows(CiConfigException.class, () -> parser.parse(PATH, "event: [unclosed"));
  }

  @Test
  public void theStepSchemaIsTheSameOneAndItsErrorsAreToo() {
    // Shared machinery, asserted rather than assumed: a step must not mean something different in a
    // trigger file than it does in a pipeline.
    CiEventTrigger trigger =
        parser.parse(
            PATH,
            """
            event: E
            steps:
              - image: alpine:3
                script: "true"
                timeout-seconds: 45
                docker: true
                name: ignored-unknown-step-key
            """);
    assertEquals(45, trigger.pipeline().steps().get(0).timeoutSeconds());
    assertTrue(trigger.pipeline().steps().get(0).docker());
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: E\nsteps:\n  - image: alpine:3\n"));
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: E\nsteps:\n  - image: a\n    script: x\n    docker: 1\n"));
  }

  // --- artifacts: what a release pipeline declares it publishes ---

  @Test
  public void aReleasePipelineDeclaresWhatItPublishes() {
    CiEventTrigger trigger =
        parser.parse(
            PATH,
            """
            event: SCMRelease
            when:
              - repository: { exact: qits-spa-ui-components }
            artifacts:
              - { type: npm, name: "@qits/ui-components" }
              - { type: maven, name: "eu.wohlben.qits:qits-eventstream" }
              - { type: docker, name: qits/qits-stt }
              - { type: daemon, name: qits-ci-daemon }
            steps:
              - image: qits/build-images/node-base:latest
                script: ./publish-tag.sh
            """);

    assertEquals(4, trigger.artifacts().size());
    assertEquals(CiArtifact.Type.NPM, trigger.artifacts().get(0).type());
    // The scope survives YAML: '@' is a reserved indicator, so the name has to be quoted and the
    // quotes are not part of it.
    assertEquals("@qits/ui-components", trigger.artifacts().get(0).name());
    assertEquals(CiArtifact.Type.MAVEN, trigger.artifacts().get(1).type());
    assertEquals("eu.wohlben.qits:qits-eventstream", trigger.artifacts().get(1).name());
    assertEquals(CiArtifact.Type.DOCKER, trigger.artifacts().get(2).type());
    // Unqualified, deliberately: no registry-qualified docker reference is portable between a step
    // container and this process.
    assertEquals("qits/qits-stt", trigger.artifacts().get(2).name());
    // A platform daemon binary: an executable qits-artifacts holds and the platform runs, named
    // bare. It is a first-class type so the one binary every CI run depends on is announced by the
    // release train like everything else it builds.
    assertEquals(CiArtifact.Type.DAEMON, trigger.artifacts().get(3).type());
    assertEquals("qits-ci-daemon", trigger.artifacts().get(3).name());
    // The keyword a repository writes is the value the wire carries — one vocabulary, not two.
    assertEquals("npm", trigger.artifacts().get(0).type().declared());
    assertEquals("maven", trigger.artifacts().get(1).type().declared());
    assertEquals("docker", trigger.artifacts().get(2).type().declared());
    assertEquals("daemon", trigger.artifacts().get(3).type().declared());
  }

  @Test
  public void mostTriggerFilesDeclareNoneAndThatIsNotAnError() {
    // An ordinary event pipeline bumps a dependency and publishes nothing. Absent means empty, which
    // is what makes the key genuinely optional rather than optional-looking.
    assertEquals(
        List.of(), parser.parse(PATH, "event: BuildSuccessful\nsteps: []\n").artifacts());
  }

  @Test
  public void anEmptyArtifactListIsAParseError() {
    // The same argument `branches: []` loses on: omitting the key already spells "publishes
    // nothing", so `[]` is an ambiguity with a better spelling.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: SCMRelease\nartifacts: []\nsteps: []\n"));
    assertTrue(e.getMessage().contains(PATH), e.getMessage());
    assertTrue(e.getMessage().contains("empty"), e.getMessage());
  }

  @Test
  public void anUnknownArtifactTypeIsAParseError() {
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () ->
                parser.parse(
                    PATH, "event: SCMRelease\nartifacts:\n  - { type: rubygems, name: qits-ci }\n"));
    assertTrue(e.getMessage().contains("rubygems"), e.getMessage());
    // The vocabulary is derived from the enum, so an added type cannot leave the error message
    // refusing a keyword it does not admit to knowing.
    assertTrue(e.getMessage().contains("npm, maven, docker, daemon and docs"), e.getMessage());
    // A missing type is the same failure: there is no default registry to fall back to.
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: SCMRelease\nartifacts:\n  - { name: qits-ci }\n"));
  }

  @Test
  public void aMissingOrBlankNameIsAParseError() {
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: SCMRelease\nartifacts:\n  - { type: npm }\n"));
    assertTrue(e.getMessage().contains("name"), e.getMessage());
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: SCMRelease\nartifacts:\n  - { type: npm, name: \"  \" }\n"));
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: SCMRelease\nartifacts:\n  - { type: npm, name: 7 }\n"));
  }

  @Test
  public void malformedArtifactsStructureIsAParseError() {
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: SCMRelease\nartifacts: everything\n"));
    assertThrows(
        CiConfigException.class,
        () -> parser.parse(PATH, "event: SCMRelease\nartifacts:\n  - just-a-string\n"));
    // Strict inside the mapping too: an artifact is exactly { type, name }, so a key nobody reads is
    // a declaration that does less than its author thinks.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () ->
                parser.parse(
                    PATH,
                    "event: SCMRelease\nartifacts:\n  - { type: npm, name: a, version: \"1.0.0\" }\n"));
    assertTrue(e.getMessage().contains("version"), e.getMessage());
  }

  @Test
  public void anUnquotedScopedNameIsAYamlErrorAndTheMessageSaysHowToSpellIt() {
    // '@' is a reserved YAML indicator, so this never reaches the artifact rules at all — but the
    // guidance a repository needs is in the name error, which is where it will look.
    assertThrows(
        CiConfigException.class,
        () ->
            parser.parse(
                PATH, "event: SCMRelease\nartifacts:\n  - { type: npm, name: @qits/x }\n"));
    CiConfigException missing =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: SCMRelease\nartifacts:\n  - { type: npm }\n"));
    assertTrue(missing.getMessage().contains("\"@qits/ui-components\""), missing.getMessage());
  }

  @Test
  public void artifactsInAPostReceiveFileIsAParseError() {
    // The third member of the two-way rule, and it earns its place for its own reason: what a
    // declaration announces is the TRIGGERING event's version, and a push carries none — so the key
    // could only ever be inert here.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () ->
                new CiConfigParser()
                    .parse("artifacts:\n  - { type: npm, name: \"@qits/x\" }\nsteps: []\n"));
    assertTrue(e.getMessage().contains("artifacts"), e.getMessage());
    assertTrue(e.getMessage().contains(CiConfigParser.CONFIG_PATH), e.getMessage());
  }

  @Test
  public void unknownKeysStayLenientInAPipelineAndStrictHere() {
    // The asymmetry is unchanged by the new key: a pipeline still carries config for a newer
    // qits-ci, a trigger file still refuses to.
    assertEquals(
        1,
        new CiConfigParser()
            .parse("steps:\n  - image: a\n    script: x\nfuture-key: whatever\n")
            .steps()
            .size());
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse(PATH, "event: SCMRelease\nartefacts:\n  - { type: npm, name: a }\n"));
    assertTrue(e.getMessage().contains("artefacts"), e.getMessage());
    assertTrue(e.getMessage().contains("'artifacts'"), e.getMessage());
  }

  // --- which files are trigger files at all ---

  @Test
  public void triggerPathsAreRecognisedAndNothingElseIs() {
    assertTrue(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-a.yml"));
    assertTrue(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-ui-components.yml"));
    assertTrue(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-v1.2_x.yml"));

    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-post-receive.yml"));
    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-.yml"));
    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-a.yaml"));
    assertFalse(CiEventTriggerParser.isTriggerPath("ci-event-a.yml"));
    assertFalse(CiEventTriggerParser.isTriggerPath(null));
  }

  @Test
  public void aHostileFileNameIsSimplyNotATriggerFile() {
    // The name comes back from a git ls-tree of ANOTHER repository's tree and goes straight into a
    // `git show` argv. What it may contain is decided here rather than trusted.
    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-../../etc/passwd.yml"));
    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-a b.yml"));
    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event--x.yml"));
    assertFalse(CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-\"quoted\".yml"));
    assertFalse(
        CiEventTriggerParser.isTriggerPath(".config/qits/ci-event-" + "x".repeat(65) + ".yml"));
  }

  @Test
  public void theTwoScopesReadDisjointSetsOfFiles() {
    // A platform trigger is the same file format one prefix over, and the prefixes do not overlap:
    // `ci-platform-event-` does not start with `ci-event-`, so a file belongs to exactly one scope
    // and one listing of the directory sorts them without a second read.
    assertTrue(
        CiTriggerScope.PLATFORM.matches(".config/qits/ci-platform-event-maintenance-bump.yml"));
    assertTrue(CiTriggerScope.REPOSITORY.matches(".config/qits/ci-event-upstream.yml"));

    assertFalse(CiTriggerScope.REPOSITORY.matches(".config/qits/ci-platform-event-x.yml"));
    assertFalse(CiTriggerScope.PLATFORM.matches(".config/qits/ci-event-x.yml"));
    assertFalse(CiTriggerScope.PLATFORM.matches(".config/qits/ci-platform-event-.yml"));
    assertFalse(CiTriggerScope.PLATFORM.matches(".config/qits/ci-platform-event-a b.yml"));
  }

  // --- checkout: -------------------------------------------------------------------------------

  @Test
  public void checkoutParsesItsTwoDotPathsAndAbsenceIsNull() {
    CiEventTrigger withCheckout =
        parser.parse(
            PATH,
            """
            event: SCMPublishCommit
            when:
              - repoId: { exact: qits-githost }
            checkout:
              branch: branch
              sha: sha
            steps:
              - image: alpine:3
                script: "true"
            """);
    assertEquals("branch", withCheckout.checkout().branchPath());
    assertEquals("sha", withCheckout.checkout().shaPath());
    assertFalse(withCheckout.checkout().optional(), "a checkout is mandatory unless it says so");

    CiEventTrigger without =
        parser.parse(PATH, "event: X\nsteps:\n  - image: alpine:3\n    script: \"true\"\n");
    assertEquals(null, without.checkout(), "absent checkout is today's main-head behavior");
  }

  /**
   * <b>The release recipe's shape, parsed.</b> {@code branch: version} points at a TAG name — the
   * key is named for the run row's ref column, not for what the value may be — and {@code optional:
   * true} is what keeps a release event from before {@code commitSha} existed from costing the
   * pipeline its run. Both are ordinary members of the grammar; nothing here knows what a tag is.
   */
  @Test
  public void aCheckoutMayNameATagAndMayDeclareItselfOptional() {
    CiEventTrigger trigger =
        parser.parse(
            PATH,
            """
            event: SCMRelease
            when:
              - repository: { exact: qits-ci-service }
            checkout:
              branch: version
              sha: commitSha
              optional: true
            steps:
              - image: alpine:3
                script: "true"
            """);
    assertEquals("version", trigger.checkout().branchPath());
    assertEquals("commitSha", trigger.checkout().shaPath());
    assertTrue(trigger.checkout().optional());
  }

  /**
   * {@code optional} is a YAML boolean or an error, {@code gating:}'s rule and for the same reason:
   * both ways of guessing are silent. A stray {@code "true"} that parsed as false would lose exactly
   * the runs the key exists to keep.
   */
  @Test
  public void aNonBooleanCheckoutOptionalIsAParseErrorNamingTheFile() {
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                parser.parse(
                    PATH,
                    "event: X\ncheckout:\n  branch: b\n  sha: s\n  optional: yes please\n"
                        + "steps:\n  - image: alpine:3\n    script: \"true\"\n"));
    assertTrue(refused.getMessage().contains(PATH), refused.getMessage());
    assertTrue(refused.getMessage().contains("checkout.optional"), refused.getMessage());
  }

  /**
   * A trigger accepted on the compatibility arm is handed on as a checkout-LESS trigger, which is
   * what {@link CiEventTrigger#withoutCheckout()} is for: every reader keyed on {@code checkout} —
   * the per-ref burst collapse above all — then gets the answer that describes the run rather than
   * the one that describes the file.
   */
  @Test
  public void aTriggerStrippedOfItsCheckoutKeepsEverythingElse() {
    CiEventTrigger declared =
        parser.parse(
            PATH,
            """
            event: SCMRelease
            checkout:
              branch: version
              sha: commitSha
              optional: true
            artifacts:
              - { type: docker, name: qits/qits-ci }
            steps:
              - image: alpine:3
                script: "true"
            """);
    CiEventTrigger stripped = declared.withoutCheckout();

    assertEquals(null, stripped.checkout());
    assertEquals(declared.eventName(), stripped.eventName());
    assertEquals(declared.configPath(), stripped.configPath());
    assertEquals(declared.artifacts(), stripped.artifacts());
    assertEquals(declared.gating(), stripped.gating());
    assertEquals(
        declared.pipeline().steps().size(),
        stripped.pipeline().steps().size(),
        "the pipeline is the same pipeline; only where it runs changed");
    assertEquals(
        stripped, stripped.withoutCheckout(), "idempotent — a checkout-less trigger is itself");
  }

  @Test
  public void aCheckoutMissingEitherPathOrCarryingAnythingElseIsAParseErrorNamingTheFile() {
    // Missing sha, missing branch, unknown key, non-map, non-string value, non-dot-path value —
    // strict in every direction: a checkout that silently parsed to nothing would build main's
    // head while claiming the event's commit.
    for (String broken :
        new String[] {
          "checkout:\n  branch: branch\n",
          "checkout:\n  sha: sha\n",
          "checkout:\n  branch: branch\n  sha: sha\n  ref: also\n",
          "checkout:\n  branch: branch\n  sha: sha\n  optionl: true\n",
          "checkout: branch\n",
          "checkout:\n  branch: branch\n  sha: [sha]\n",
          "checkout:\n  branch: branch\n  sha: \"payload[0]\"\n",
        }) {
      CiConfigException refused =
          assertThrows(
              CiConfigException.class,
              () ->
                  parser.parse(
                      PATH,
                      "event: X\n"
                          + broken
                          + "steps:\n  - image: alpine:3\n    script: \"true\"\n"),
              broken);
      assertTrue(refused.getMessage().contains(PATH), refused.getMessage());
    }
  }

  @Test
  public void checkoutIsNoLongerAnUnknownTopLevelKeyAndTheRejectionNamesIt() {
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                parser.parse(
                    PATH,
                    "event: X\ncheckut: {branch: b, sha: s}\n"
                        + "steps:\n  - image: alpine:3\n    script: \"true\"\n"));
    assertTrue(refused.getMessage().contains("'checkout'"), refused.getMessage());
  }

  // --- per-step gating: ------------------------------------------------------------------------

  @Test
  public void aStepDeclaresItsOwnGatingAndAbsenceIsGating() {
    // The one-file QA pipeline's shape: a gating build followed by a non-gating publish. What the
    // two halves used to buy with two files, ordering plus this key buys inside one.
    CiEventTrigger trigger =
        parser.parse(
            PATH,
            """
            event: ReleaseRequestChanged
            checkout:
              branch: backingBranch
              sha: mergedSha
            steps:
              - image: alpine:3
                script: verify
              - image: alpine:3
                gating: false
                script: publish-userflows
            """);
    assertTrue(trigger.gating(), "the FILE is gating; only the second step is not");
    assertTrue(trigger.pipeline().steps().get(0).gating());
    assertFalse(trigger.pipeline().steps().get(1).gating());
  }

  @Test
  public void aStepGatingThatIsNotABooleanIsAParseError() {
    // The sharper of the two standing reasons: `gating: "false"` parsing as truthy would hold a
    // commit for a failure nobody meant to gate on, and the other direction waves one through.
    CiConfigException refused =
        assertThrows(
            CiConfigException.class,
            () ->
                parser.parse(
                    PATH,
                    "event: X\nsteps:\n  - image: alpine:3\n    gating: \"false\"\n"
                        + "    script: \"true\"\n"));
    assertTrue(refused.getMessage().contains("gating"), refused.getMessage());
  }
}
