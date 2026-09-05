package eu.wohlben.qits.ci.control;

import java.util.List;

/**
 * One parsed {@code .config/qits/ci-event-*.yml}: the event name it listens for, the selection over
 * that event's payload, the pipeline to run when both hold, and what that pipeline publishes.
 *
 * <p>{@code artifacts} is empty for every trigger file that declares none, which is most of them —
 * an ordinary event pipeline bumps a dependency and publishes nothing. A non-empty list makes this a
 * <b>release pipeline</b>: a green run announces one {@code SoftwareRelease} per entry, carrying the
 * version out of the event that triggered it. See {@link CiArtifact}.
 *
 * <p>{@code configPath} is the file it came from — the {@code *} is freely chosen and completely
 * ignored as a selector (it names the trigger for humans), but the path itself is <b>identity</b>:
 * it is recorded on the run and it is one third of the unique constraint that makes a triggered run
 * at-most-once. Two different trigger files in one repository matching the same event are two runs
 * by design; they are two declared pipelines.
 *
 * <p>{@code checkout} is null for every file that declares none — the run then builds the head of
 * {@code main}, as every event trigger always has. Declared, the run builds <b>the commit the
 * event names</b>: the two paths are dot-paths into the payload, resolved per event.
 *
 * <p>{@code gating} is {@code true} unless the file says {@code gating: false} — whether a red run
 * of this pipeline should stand in the way of releasing its commit. It rides the run row onto the
 * build events, where the release-quality-gates build gate reads it; the userflow pipelines are the
 * ones that say false.
 */
public record CiEventTrigger(
    String configPath,
    String eventName,
    CiEventSelection selection,
    CiPipeline pipeline,
    List<CiArtifact> artifacts,
    boolean gating,
    Checkout checkout) {

  /**
   * Where a run of this trigger checks out: two payload dot-paths. Null (the key absent) = main's
   * head.
   *
   * <p><b>{@code branchPath} resolves to a REF NAME, not necessarily to a branch.</b> The value
   * reaches the daemon as {@code git clone --branch}, which takes a tag exactly as happily as a
   * head, and the sha beside it is what the subsequent {@code git checkout --detach} lands on. That
   * is what lets a release pipeline anchor at {@code checkout: { branch: version, sha: commitSha }}
   * — the tag's own name and the commit it points at — instead of being recorded at {@code main} and
   * going to find the released tree inside its own step script. The engine learns nothing about tags
   * to make this work and should not: a ref name is a ref name, and the column is called {@code
   * branch} because that is what the run row has always called its ref.
   *
   * <p>{@code optional} is {@code false} unless the file says {@code optional: true}: whether an
   * event that does not carry both values may still run, at main's head, rather than costing this
   * file its run. See {@link CiConfigSchema#CHECKOUT_OPTIONAL} — it is the arm that keeps an
   * additive event field additive.
   */
  public record Checkout(String branchPath, String shaPath, boolean optional) {}

  /**
   * This trigger as a run that did <b>not</b> follow the event's ref is accepted under — the
   * declaration minus its {@code checkout}.
   *
   * <p>It exists for one caller: {@code CiEventTriggerService}'s optional-checkout fallback, where
   * the event carries no coordinate and the run is recorded at {@code main}'s head. Such a run IS a
   * checkout-less run, and handing on a trigger that still says otherwise would leave every reader
   * downstream deciding from the declaration instead of from what happened — the per-ref burst
   * collapse in {@code CiRunService} being the one that would then dedupe two distinct events
   * sharing the {@code main} convention.
   *
   * <p>The row's {@code triggerConfig} still holds the file verbatim, so a restart reparses the
   * declaration and this narrowing is not durable. That is correct rather than a gap: it is a fact
   * about one acceptance, and acceptance is the only moment anything asks.
   */
  public CiEventTrigger withoutCheckout() {
    return checkout == null
        ? this
        : new CiEventTrigger(
            configPath, eventName, selection, pipeline, artifacts, gating, null);
  }
}
