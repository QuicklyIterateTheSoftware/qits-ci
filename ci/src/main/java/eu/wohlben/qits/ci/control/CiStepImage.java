package eu.wohlben.qits.ci.control;

/**
 * Where a step's image is pulled from, when the recipe named it without a registry.
 *
 * <p><b>The problem this exists for.</b> Every recipe on the platform opens with {@code image:
 * qits/build-images/ci-base:latest} — a bare name, deliberately, so a recipe states no deployment
 * fact. Docker resolves a bare name against Docker Hub, so that reference only ever worked because
 * the image happened to be sitting in the host's local store, put there by the bootstrap. That made
 * the whole CI plane un-rebuildable by the platform's own automation: prune the host and every
 * build in every repository fails with {@code pull access denied for qits/build-images/ci-base}, and
 * the only recovery was a bootstrap rerun. It is also circular — the pipeline that publishes these
 * images runs <i>on</i> one of them.
 *
 * <p><b>The fix.</b> qits-oci already publishes every step image to the platform registry, under
 * {@code <registry>/<repository>/build-images/<name>}, on both the release CalVer and {@code
 * latest}. The bare name in a recipe is exactly that path minus the registry host, so resolving one
 * to the other is a prefix and nothing more. A pruned host then re-pulls what it lost, which is what
 * makes these ordinary published images rather than bootstrap residue.
 *
 * <p><b>Only the platform's own namespace is redirected</b>, and that narrowness is the safety
 * property. A reference that already names a registry is untouched; so is a single-segment official
 * image like {@code docker:cli}, which is what these Dockerfiles are built {@code FROM}. Only a
 * first path segment equal to the configured image repository is claimed — that is the platform's
 * own name for its own artifacts, and nothing else can collide with it without already being ours.
 *
 * <p><b>Why centrally rather than in the recipes.</b> Twenty repositories spell this image, and
 * writing the registry host into each would put one deployment fact in twenty files and break the
 * property that a recipe is registry-independent — the same property the publish scripts keep by
 * composing {@code $QITS_REGISTRY} rather than naming a host. The cost is that a mistake here breaks
 * every repository's builds at once, which is the same blast radius the publishing pipeline already
 * carries and the reason this rule is kept small enough to read in one sitting.
 *
 * <p>Pulling from that registry is a proven path rather than a hope: qits-platform-deployments runs
 * every deployed container from {@code <registry>/<repository>/<application>:<sha>} through this
 * same orchestrator and the same docker daemon.
 */
public final class CiStepImage {

  private CiStepImage() {}

  /**
   * The reference to start, given the platform's registry coordinates.
   *
   * @param image the image as the recipe spelled it
   * @param registryHost {@code qits.artifacts.registry-host}, the host the platform publishes to
   * @param imageRepository {@code qits.artifacts.image-repository}, the platform's own namespace
   * @return the same reference, or one prefixed with the registry when it names a platform image
   */
  public static String resolve(String image, String registryHost, String imageRepository) {
    if (image == null
        || image.isBlank()
        || registryHost == null
        || registryHost.isBlank()
        || imageRepository == null
        || imageRepository.isBlank()) {
      return image;
    }
    int slash = image.indexOf('/');
    // No slash at all is an official Docker Hub image — `docker:cli`, `alpine:3`. Never ours.
    if (slash < 0) {
      return image;
    }
    String first = image.substring(0, slash);
    // A first segment that looks like a host already names a registry, and a reference that names
    // its registry is a decision the recipe made. `localhost` is spelled out because it carries
    // neither a dot nor a colon and would otherwise read as a path segment.
    if (first.indexOf('.') >= 0 || first.indexOf(':') >= 0 || "localhost".equals(first)) {
      return image;
    }
    return imageRepository.equals(first) ? registryHost + "/" + image : image;
  }
}
