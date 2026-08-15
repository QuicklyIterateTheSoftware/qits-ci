package eu.wohlben.qits.ci.githost;

import java.util.Optional;

/** The access token carried on qits-ci's reads from qits-githost. */
@FunctionalInterface
interface GitHostBearer {
  Optional<String> token();
}
