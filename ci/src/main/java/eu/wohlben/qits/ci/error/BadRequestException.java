package eu.wohlben.qits.ci.error;

/** 400. */
public class BadRequestException extends CiException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
