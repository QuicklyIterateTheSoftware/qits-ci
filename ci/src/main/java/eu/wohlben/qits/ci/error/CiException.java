package eu.wohlben.qits.ci.error;

/**
 * Base for ci errors. Carries an HTTP-ish status code so the web layer can map it to a response
 * without ci depending on JAX-RS (the same framework-free stance as {@code domain.error} — but ci
 * must not depend on {@code domain}, so it owns its own). The {@code service} module maps these via
 * {@code CiExceptionMapper}.
 */
public class CiException extends RuntimeException {

  private final int statusCode;

  public CiException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public CiException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
