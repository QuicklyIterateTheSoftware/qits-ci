package eu.wohlben.qits.ci.security;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Blanks the {@code %test} dev-user fallback this module ships, so a test sees the deployed
 * posture: no header ⇒ anonymous. (An empty value reads as absent for the {@code Optional} config
 * property.)
 */
public class NoDevUserProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.auth.forward.dev-user", "");
  }
}
