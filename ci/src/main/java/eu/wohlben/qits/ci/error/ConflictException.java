package eu.wohlben.qits.ci.error;

/** 409 — the request is well-formed but the thing it addresses is in the wrong state for it. */
public class ConflictException extends CiException {

  public ConflictException(String message) {
    super(409, message);
  }
}
