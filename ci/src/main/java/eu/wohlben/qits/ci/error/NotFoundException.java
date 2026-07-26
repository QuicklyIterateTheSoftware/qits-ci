package eu.wohlben.qits.ci.error;

/** 404. */
public class NotFoundException extends CiException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
