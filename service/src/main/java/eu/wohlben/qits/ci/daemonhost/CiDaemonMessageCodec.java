package eu.wohlben.qits.ci.daemonhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonCodec;
import eu.wohlben.qits.cidaemon.protocol.CiDaemonMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * The host's bridge between a {@link CiDaemonMessage} and its JSON text frame. The vendored,
 * framework-free {@link CiDaemonCodec} does the field mapping to/from a {@code Map}; this class only
 * bolts on Jackson (the same {@code ObjectMapper} the rest of {@code service} uses), so the wire
 * contract stays owned by {@code ci-daemon-protocol} and is never re-spelled here. The {@code
 * ci-daemon} binary does the symmetric job with a Vert.x {@code JsonObject}.
 *
 * <p>{@link #decode} deliberately lets the shared codec's strictness through as an exception rather
 * than softening it: an unknown or missing {@code type}, an unknown {@code InitFailed} reason, and
 * an absent {@code stream} all throw. {@link CiDaemonSocket} is where that is caught — one
 * undecodable frame from a container must cost that frame and nothing else, and a container is
 * exactly the place a malformed frame comes from.
 */
@ApplicationScoped
public class CiDaemonMessageCodec {

  @Inject ObjectMapper objectMapper;

  /** Serialize a message to the JSON text sent over the socket. */
  public String encode(CiDaemonMessage message) {
    try {
      return objectMapper.writeValueAsString(CiDaemonCodec.encode(message));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode ci-daemon message", e);
    }
  }

  /** Parse a received JSON text frame back into a message. */
  @SuppressWarnings("unchecked")
  public CiDaemonMessage decode(String json) {
    try {
      return CiDaemonCodec.decode(objectMapper.readValue(json, Map.class));
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to decode ci-daemon message", e);
    }
  }
}
