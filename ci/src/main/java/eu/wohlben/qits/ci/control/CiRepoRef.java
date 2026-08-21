package eu.wohlben.qits.ci.control;

/**
 * How qits-ci addresses one repository: the storage id it has always used, plus the public
 * {@code (projectId, name)} coordinate the platform speaks above the projects↔githost seam.
 *
 * <p><b>Both coordinates, because the seam moved and the id did not.</b> The git host's repository
 * key is an opaque UUID minted by qits-projects, and the one public clone address is {@code
 * /git/<projectId>/<repoName>}. A run still records the id — it is the storage-adjacent key, it is
 * what every existing row carries, and it is what the dedupe constraint is built on — but every URL
 * a run builds, and every name a person reads, comes from the pair.
 *
 * <p><b>The name half is nullable and that is the compatibility arm, not an oversight.</b> The
 * git host fills {@code projectId}/{@code repoName} on an {@code SCM*} event from the push address,
 * so a push on the internal id-addressed route announces without them; and a repository qits-ci
 * knows only from its own run rows has no name to offer either. {@link #named()} is the one question
 * every caller asks: with the pair, address by name; without it, address by id exactly as this
 * service always did. Pre-cutover the two agree on the live platform, so the fallback is correct
 * rather than merely tolerated — and post-cutover every public push carries names, so it goes quiet.
 *
 * @param repoId the git host's storage id — an opaque UUID after the cutover, a slug before it
 * @param projectId the owning project, or null when the event or the listing carried none
 * @param name the repository's addressable name within that project, or null for the same reason
 */
public record CiRepoRef(String repoId, String projectId, String name) {

  /** A repository known by its storage id alone — the id-addressed arm. */
  public static CiRepoRef of(String repoId) {
    return new CiRepoRef(repoId, null, null);
  }

  /**
   * The same, with whatever name coordinate the caller has. Blank is normalised to null, because a
   * blank projectId is not a coordinate and a caller that has to check for both spellings will
   * eventually check for one.
   */
  public static CiRepoRef of(String repoId, String projectId, String name) {
    return new CiRepoRef(repoId, blankToNull(projectId), blankToNull(name));
  }

  /** Whether this reference carries the full public coordinate, so a name-addressed URL is possible. */
  public boolean named() {
    return projectId != null && !projectId.isBlank() && name != null && !name.isBlank();
  }

  /** What a person should see: the name when there is one, the storage id when there is not. */
  public String display() {
    return named() ? name : repoId;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
