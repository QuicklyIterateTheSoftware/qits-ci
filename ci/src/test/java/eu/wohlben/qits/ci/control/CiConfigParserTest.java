package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
