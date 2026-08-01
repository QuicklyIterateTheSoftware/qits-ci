package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The parser is pure — plain JUnit, no Quarkus. */
public class CiConfigParserTest {

  private final CiConfigParser parser = new CiConfigParser();

  @Test
  public void parsesTheTwoStepHappyPath() {
    CiPipeline pipeline =
        parser.parse(
            """
            steps:
              - image: maven:3.9-eclipse-temurin-25
                script: ./mvnw verify
              - image: node:22
                script: pnpm test
            """);
    assertEquals(2, pipeline.steps().size());
    assertEquals("maven:3.9-eclipse-temurin-25", pipeline.steps().get(0).image());
    assertEquals("./mvnw verify", pipeline.steps().get(0).script());
    assertEquals("node:22", pipeline.steps().get(1).image());
    assertEquals("pnpm test", pipeline.steps().get(1).script());
  }

  @Test
  public void preservesMultiLineBlockScalarScripts() {
    CiPipeline pipeline =
        parser.parse(
            """
            steps:
              - image: node:22
                script: |
                  corepack enable
                  pnpm install --frozen-lockfile
                  pnpm test
            """);
    assertEquals(
        "corepack enable\npnpm install --frozen-lockfile\npnpm test\n",
        pipeline.steps().get(0).script());
  }

  @Test
  public void ignoresUnknownKeysAtBothLevels() {
    // Leniency: a repo may carry config for a newer qits-ci — unknown keys are never read.
    CiPipeline pipeline =
        parser.parse(
            """
            version: 99
            cache: aggressive
            steps:
              - image: alpine:3
                script: "true"
                name: lint
                needs: [something]
            """);
    assertEquals(1, pipeline.steps().size());
    assertEquals("alpine:3", pipeline.steps().get(0).image());
  }

  @Test
  public void anAbsentTimeoutMeansTheDeploymentsDefault() {
    // Null rather than a number: the parser does not know what a deployment configured, and the
    // absent field has to keep meaning exactly what it meant before the key existed.
    CiPipeline pipeline = parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n");
    assertNull(pipeline.steps().get(0).timeoutSeconds());
  }

  @Test
  public void aDeclaredTimeoutIsReadPerStep() {
    CiPipeline pipeline =
        parser.parse(
            """
            steps:
              - image: alpine:3
                script: "true"
                timeout-seconds: 45
              - image: alpine:3
                script: "true"
            """);
    assertEquals(45, pipeline.steps().get(0).timeoutSeconds());
    assertNull(pipeline.steps().get(1).timeoutSeconds());
  }

  @Test
  public void anUnusableTimeoutIsAConfigErrorRatherThanIgnored() {
    // The leniency above is about keys this parser does not KNOW. It knows this one, so a repo that
    // meant to bound a step and mistyped the number must find out rather than silently get 900s.
    assertThrows(
        CiConfigException.class,
        () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    timeout-seconds: soon\n"));
    assertThrows(
        CiConfigException.class,
        () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    timeout-seconds: 0\n"));
    assertThrows(
        CiConfigException.class,
        () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    timeout-seconds: -5\n"));
  }

  @Test
  public void anAbsentDockerFlagMeansNoSocket() {
    // False is not a default this parser chose — it is the sandbox every step has always had, and an
    // absent key has to keep meaning exactly that.
    CiPipeline pipeline = parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n");
    assertFalse(pipeline.steps().get(0).docker());
  }

  @Test
  public void aDeclaredDockerFlagIsReadPerStep() {
    // The publishing shape: build steps as they always were, then one final step that gets the host's
    // docker socket and pushes. The flag is per step, so opting one in leaves the others alone.
    CiPipeline pipeline =
        parser.parse(
            """
            steps:
              - image: alpine:3
                script: ./mvnw -B -ntp verify
              - image: alpine:3
                docker: true
                script: |
                  docker build -t "$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/app:$QITS_CI_SHA" .
                  docker push "$QITS_REGISTRY/$QITS_IMAGE_REPOSITORY/app:$QITS_CI_SHA"
            """);
    assertFalse(pipeline.steps().get(0).docker());
    assertTrue(pipeline.steps().get(1).docker());
  }

  @Test
  public void anUnusableDockerFlagIsAConfigErrorRatherThanIgnored() {
    // Known key, same standard as timeout-seconds, and a sharper reason for it: the flag is the one
    // privilege a repository can ask for, so a repo that mistyped it must not be left believing it
    // opted in — nor, worse, get a socket out of a string that merely looks true.
    assertThrows(
        CiConfigException.class,
        () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    docker: yes-please\n"));
    assertThrows(
        CiConfigException.class,
        () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    docker: 1\n"));
    // YAML's own boolean spellings are booleans, so the strictness costs a repo nothing it wanted.
    assertTrue(
        parser
            .parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    docker: yes\n")
            .steps()
            .get(0)
            .docker());
  }

  // --- the per-step branch filter ---

  @Test
  public void anAbsentBranchesKeyRunsTheStepOnEveryBranch() {
    // The whole backward-compatibility clause: every pipeline written before this key existed keeps
    // its behaviour byte for byte, and "byte for byte" is asserted rather than described.
    CiPipeline.CiStepDecl step =
        parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n").steps().get(0);
    assertEquals(List.of(), step.branches());
    assertTrue(step.runsOnBranch("main"));
    assertTrue(step.runsOnBranch("maintenance/qits-spa-ui-components"));
    assertTrue(step.runsOnBranch("task/anything"));
  }

  @Test
  public void aDeclaredBranchFilterBindsTheStepAndLeavesItsNeighboursAlone() {
    // The release train's own shape: the tests run on every push, the release step only on the
    // branch a bump pipeline force-pushed.
    CiPipeline pipeline =
        parser.parse(
            """
            steps:
              - image: node-base:latest
                script: npm test
              - image: node-base:latest
                branches:
                  - prefix: maintenance/
                script: ./release.sh
            """);
    assertTrue(pipeline.steps().get(0).runsOnBranch("main"), "an unscoped step binds every branch");
    assertTrue(pipeline.steps().get(1).runsOnBranch("maintenance/qits-spa-ui-components"));
    assertFalse(pipeline.steps().get(1).runsOnBranch("main"));
    assertFalse(pipeline.steps().get(1).runsOnBranch("maintenance"), "a prefix is not a substring");
  }

  @Test
  public void entriesAreOrdAndAMappingsKeysAreAnded() {
    // The when: DSL's composition rule, minus the path level, because the subject is one scalar.
    CiPipeline.CiStepDecl ord =
        parser
            .parse(
                """
                steps:
                  - image: alpine:3
                    script: "true"
                    branches:
                      - exact: main
                      - prefix: maintenance/
                """)
            .steps()
            .get(0);
    assertTrue(ord.runsOnBranch("main"));
    assertTrue(ord.runsOnBranch("maintenance/x"));
    assertFalse(ord.runsOnBranch("task/x"));

    CiPipeline.CiStepDecl anded =
        parser
            .parse(
                """
                steps:
                  - image: alpine:3
                    script: "true"
                    branches:
                      - prefix: maintenance/
                        exact: maintenance/qits-spa-angular
                """)
            .steps()
            .get(0);
    assertTrue(anded.runsOnBranch("maintenance/qits-spa-angular"));
    assertFalse(anded.runsOnBranch("maintenance/other"), "both matchers in a mapping must hold");
  }

  @Test
  public void anEmptyBranchesListIsAConfigError() {
    // Both readings of `[]` already have an unambiguous spelling — omit the key, delete the step —
    // and an ambiguity with two better spellings is a parse error rather than a guess.
    CiConfigException e =
        assertThrows(
            CiConfigException.class,
            () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    branches: []\n"));
    assertTrue(e.getMessage().contains("branches"), e.getMessage());
  }

  @Test
  public void aMalformedBranchFilterIsAConfigErrorRatherThanIgnored() {
    // Known key, same standard as timeout-seconds and docker, and the sharpest reason of the three:
    // a silently mis-parsed filter either runs a scoped step everywhere or skips it forever, and
    // both directions are silent.
    for (String branches :
        List.of(
            "branches: maintenance/", // not a list
            "branches:\n      - maintenance/", // an entry that is not a matcher mapping
            "branches:\n      - {}", // an entry that asserts nothing
            "branches:\n      - regex: main.*", // a matcher this vocabulary does not have
            "branches:\n      - exists: true", // excluded: a branch is always there
            "branches:\n      - exact: 3", // matcher values are strings
            "branches:\n      - prefix: \"\"")) { // and non-empty ones
      assertThrows(
          CiConfigException.class,
          () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"true\"\n    " + branches + "\n"),
          branches);
    }
  }

  @Test
  public void anUnknownKeyBesideABranchFilterIsStillIgnored() {
    // The leniency this key joins rather than replaces: `branches` is known and strict, everything
    // the parser does not know stays unread.
    CiPipeline.CiStepDecl step =
        parser
            .parse(
                """
                steps:
                  - image: alpine:3
                    script: "true"
                    branches:
                      - exact: main
                    name: lint
                    needs: [something]
                """)
            .steps()
            .get(0);
    assertTrue(step.runsOnBranch("main"));
    assertFalse(step.runsOnBranch("task/x"));
  }

  @Test
  public void malformedYamlIsAConfigError() {
    assertThrows(CiConfigException.class, () -> parser.parse("steps: [unclosed"));
  }

  @Test
  public void nonMappingRootIsAConfigError() {
    assertThrows(CiConfigException.class, () -> parser.parse("- just\n- a\n- list\n"));
  }

  @Test
  public void nonListStepsIsAConfigError() {
    assertThrows(CiConfigException.class, () -> parser.parse("steps: run-everything\n"));
  }

  @Test
  public void nonMappingStepEntryIsAConfigError() {
    assertThrows(CiConfigException.class, () -> parser.parse("steps:\n  - 42\n"));
  }

  @Test
  public void missingScriptIsAConfigError() {
    CiConfigException e =
        assertThrows(CiConfigException.class, () -> parser.parse("steps:\n  - image: alpine:3\n"));
    assertTrue(e.getMessage().contains("script"), e.getMessage());
  }

  @Test
  public void missingImageIsAConfigError() {
    CiConfigException e =
        assertThrows(
            CiConfigException.class, () -> parser.parse("steps:\n  - script: ./mvnw verify\n"));
    assertTrue(e.getMessage().contains("image"), e.getMessage());
  }

  @Test
  public void blankScriptIsAConfigError() {
    assertThrows(
        CiConfigException.class,
        () -> parser.parse("steps:\n  - image: alpine:3\n    script: \"\"\n"));
  }

  @Test
  public void emptyContentAndEmptyStepsYieldAnEmptyPipeline() {
    assertEquals(0, parser.parse(null).steps().size());
    assertEquals(0, parser.parse("   ").steps().size());
    assertEquals(0, parser.parse("# only a comment\n").steps().size());
    assertEquals(0, parser.parse("steps: []\n").steps().size());
    assertEquals(0, parser.parse("other: config\n").steps().size());
  }
}
