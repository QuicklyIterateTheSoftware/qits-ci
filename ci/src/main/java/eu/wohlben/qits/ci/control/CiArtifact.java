package eu.wohlben.qits.ci.control;

/**
 * One artifact a trigger file declares its pipeline publishes: {@code {type: npm, name:
 * "@qits/ui-components"}}.
 *
 * <p><b>Declared, not observed</b>, and that is the decision rather than a shortcut. qits-ci never
 * learns how to publish anything — every {@code npm publish}, {@code mvn deploy}, and {@code docker
 * push} on this platform lives in a step script inside the repository's own container — so what a
 * run published is not a thing this process can see. It could have been reported back, and it
 * deliberately is not:
 * the daemon's return channel carries only {@code StepChunk} and {@code StepFinished}, a stdout
 * sentinel is forbidden by design, and an emit-based scheme would have been a two-repo protocol
 * change. A declaration costs none of that and buys the thing an emission never could — it can be
 * <b>read statically</b>, so the cross-repo dependency graph the parked cycle-detection work needs
 * is derivable from the trigger files alone, without running a single pipeline.
 *
 * <p>The price is honest and worth naming: a declaration can lie. A pipeline that goes green without
 * publishing announces an artifact that is not there. Nothing here checks — post-hoc verification is
 * possible (docker answers {@code HEAD /v2/<repo>/<image>/manifests/<tag>}, npm has no per-version
 * route but lists versions) and is not built.
 */
public record CiArtifact(Type type, String name) {

  /**
   * The registries a declaration may name. <b>The constant's declared spelling is also its wire
   * value</b> — {@code type: npm} in the file becomes {@code "packageType": "npm"} in the published
   * {@code SoftwareRelease} — so the vocabulary exists once and cannot drift between the parser and
   * the event.
   *
   * <p>Maven names a published GAV, for example {@code eu.wohlben.qits:qits-eventstream}. The
   * repository host is omitted for the same portability reason as docker's: the consumer supplies
   * the address from its own environment.
   */
  public enum Type {
    NPM("npm"),
    MAVEN("maven"),
    DOCKER("docker");

    private final String declared;

    Type(String declared) {
      this.declared = declared;
    }

    /** How a trigger file spells it, and how the wire spells it. */
    public String declared() {
      return declared;
    }

    /** The type this keyword names, or null — the parser turns null into the file's parse error. */
    static Type of(String keyword) {
      for (Type type : values()) {
        if (type.declared.equals(keyword)) {
          return type;
        }
      }
      return null;
    }

    /** The vocabulary as a message fragment, so an error names what this qits-ci knows. */
    static String vocabulary() {
      return NPM.declared + ", " + MAVEN.declared + " and " + DOCKER.declared;
    }
  }
}
