package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import eu.wohlben.qits.ci.control.CiEventSelection.Group;
import eu.wohlben.qits.ci.control.CiEventSelection.Matcher;
import eu.wohlben.qits.ci.control.CiEventSelection.PathCondition;
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
}
