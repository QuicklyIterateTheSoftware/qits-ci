package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.ci.control.CiConfigParser.CiConfigException;
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
