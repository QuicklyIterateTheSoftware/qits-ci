package eu.wohlben.qits.ci.control;

/**
 * A structural problem in a repository-committed pipeline file — unparseable YAML, a {@code steps}
 * that is not a list, a step with no {@code script}/{@code image}, a key this parser knows carrying
 * a value it cannot read.
 *
 * <p>It is a <b>top-level</b> class rather than a member of the parser that throws it, because there
 * is no longer one parser that owns it. It used to be {@code CiConfigParser.CiConfigException} — the
 * push pipeline's parser — while {@link CiConfigSchema} and {@link CiEventTriggerParser} both threw
 * it from the other side of the same schema; the push parser retired with per-push CI on 2026-09-05
 * and the exception outlived it, so it now sits where the thing it describes does: beside the schema
 * every committed pipeline file shares.
 *
 * <p>What a caller does with one is unchanged. {@code CiEventTriggerService} treats it as a trigger
 * file that declares nothing runnable: one WARN naming the file, and no run — a repository must not
 * be able to make this service record a row by committing broken YAML.
 */
public class CiConfigException extends RuntimeException {

  public CiConfigException(String message) {
    super(message);
  }

  public CiConfigException(String message, Throwable cause) {
    super(message, cause);
  }
}
