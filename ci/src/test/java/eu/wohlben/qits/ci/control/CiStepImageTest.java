package eu.wohlben.qits.ci.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The prefix rule, at its edges. Every recipe on the platform depends on this being right, so the
 * cases that must NOT be rewritten are worth as much space as the one that must.
 */
class CiStepImageTest {

  private static final String REGISTRY = "qits-platform-artifacts:8080";
  private static final String REPOSITORY = "qits";

  private static String resolve(String image) {
    return CiStepImage.resolve(image, REGISTRY, REPOSITORY);
  }

  @Test
  void aPlatformImageGainsTheRegistry() {
    // The reference every recipe on the platform opens with.
    assertEquals(
        "qits-platform-artifacts:8080/qits/build-images/ci-base:latest",
        resolve("qits/build-images/ci-base:latest"));
    assertEquals(
        "qits-platform-artifacts:8080/qits/build-images/maven-base:latest",
        resolve("qits/build-images/maven-base:latest"));
    // A CalVer pin resolves the same way; the tag is not read.
    assertEquals(
        "qits-platform-artifacts:8080/qits/build-images/node-base:2026.820.131511",
        resolve("qits/build-images/node-base:2026.820.131511"));
  }

  @Test
  void anImageThatAlreadyNamesARegistryIsLeftAlone() {
    // The recipe made a decision; the platform does not overrule it.
    assertEquals(
        "mirror.dev.localhost:8080/qits/build-images/ci-base:latest",
        resolve("mirror.dev.localhost:8080/qits/build-images/ci-base:latest"));
    assertEquals(
        "qits-platform-artifacts:8080/qits/build-images/ci-base:latest",
        resolve("qits-platform-artifacts:8080/qits/build-images/ci-base:latest"));
    // localhost carries neither a dot nor a colon, so it is named explicitly or it would read as a
    // path segment and be prefixed.
    assertEquals("localhost/qits/thing:latest", resolve("localhost/qits/thing:latest"));
    assertEquals("localhost:8081/qits/thing:latest", resolve("localhost:8081/qits/thing:latest"));
  }

  @Test
  void anOfficialImageIsLeftAlone() {
    // What these Dockerfiles are built FROM. A single segment is never ours.
    assertEquals("docker:cli", resolve("docker:cli"));
    assertEquals("alpine:3", resolve("alpine:3"));
    assertEquals("postgres:18", resolve("postgres:18"));
  }

  @Test
  void anotherNamespaceIsLeftAlone() {
    // Only the platform's own repository name is claimed.
    assertEquals("library/postgres:18", resolve("library/postgres:18"));
    assertEquals("someoneelse/build-images/ci-base:latest",
        resolve("someoneelse/build-images/ci-base:latest"));
    // A near-miss on the namespace is still somebody else's.
    assertEquals("qits-extra/thing:latest", resolve("qits-extra/thing:latest"));
  }

  @Test
  void nothingToResolveAgainstLeavesTheImageAsItWas() {
    assertEquals(null, resolve(null));
    assertEquals("", resolve(""));
    assertEquals(
        "qits/build-images/ci-base:latest",
        CiStepImage.resolve("qits/build-images/ci-base:latest", null, REPOSITORY));
    assertEquals(
        "qits/build-images/ci-base:latest",
        CiStepImage.resolve("qits/build-images/ci-base:latest", REGISTRY, ""));
  }
}
