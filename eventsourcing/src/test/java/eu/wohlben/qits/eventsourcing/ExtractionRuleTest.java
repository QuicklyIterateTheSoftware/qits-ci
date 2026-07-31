package eu.wohlben.qits.eventsourcing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * THE EXTRACTION RULE, as a build failure rather than a review comment.
 *
 * <p>This module is a library that lives in qits-ci because qits-ci is its first consumer, and the
 * whole value of that arrangement is that lifting it out later is a {@code git mv} plus a pom. One
 * import of {@code eu.wohlben.qits.ci.*} anywhere in here — main or test — silently turns that into
 * a refactor, and it is the kind of thing that gets added in the moment because the class was right
 * there.
 *
 * <p>The event classes qits-ci emits live one module over, in {@code ci-events}, which depends on
 * this one. The arrow points that way and only that way.
 *
 * <p>Grepping the sources rather than the classpath is deliberate: a compile-time dependency would
 * be caught by the pom, and this catches the thing the pom cannot — a source file reaching for a
 * type that some transitive jar happened to bring along.
 */
class ExtractionRuleTest {

  /** The package this module may not know about. Its own {@code ci-events} sibling is fine. */
  private static final String FORBIDDEN = "eu.wohlben.qits.ci.";

  @Test
  void noSourceInThisModuleMentionsQitsCi() throws IOException {
    List<Path> roots =
        List.of(Path.of("src/main/java"), Path.of("src/test/java")).stream()
            .filter(Files::isDirectory)
            .toList();
    assertEquals(2, roots.size(), "expected this module's sources at src/{main,test}/java");

    List<String> offenders = new ArrayList<>();
    for (Path root : roots) {
      try (Stream<Path> sources = Files.walk(root)) {
        for (Path source :
            sources
                .filter(p -> p.toString().endsWith(".java"))
                // This file spells the forbidden package on purpose; it is the only one allowed to.
                .filter(p -> !p.getFileName().toString().equals("ExtractionRuleTest.java"))
                .toList()) {
          List<String> lines = Files.readAllLines(source);
          for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(FORBIDDEN)) {
              offenders.add(source + ":" + (i + 1) + " " + lines.get(i).strip());
            }
          }
        }
      }
    }

    assertTrue(
        offenders.isEmpty(),
        "the eventsourcing module must not know about "
            + FORBIDDEN
            + "*; extracting it is meant to be a `git mv`:\n  "
            + String.join("\n  ", offenders));
  }
}
