package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * The bus end of the trigger engine: every frame, handed to {@link CiEventTriggerService} as four
 * plain strings.
 *
 * <p>It is the third bean in this package and the same shape as the other two — {@code
 * BuildSuccessfulAnnouncer} adapts a {@code ci/control} port outward, this adapts the bus inward, and
 * both exist so the {@code ci} module never imports {@code eu.wohlben.qits.eventstream}. Registration
 * is "be a bean": {@code EventDispatcher} injects {@code Instance<QitsRawEventListener>}, which is
 * what ArC counts as a use, so no {@code @Unremovable} and no annotation beyond the scope.
 *
 * <h2>{@code "*"}, permanently</h2>
 *
 * <p>{@link #signatures()} answers {@code Set.of(ALL)} and always will. The event names this engine
 * cares about live in {@code .config/qits/ci-event-*.yml} files inside <em>other</em> repositories and
 * change with every push, so they are unknowable at startup — but that is only half the argument, and
 * the other half is what forces the answer. The <b>wire</b> set is derived when the connection is
 * opened, and the subscriber does not dial at all when the union is empty; a listener that answered
 * {@code Set.of()} until it had read some config would therefore never open a stream to read config
 * about. {@code "*"} is the seam's documented idiom for exactly this: subscribe to everything, filter
 * in the engine.
 *
 * <p>What it costs is one line of the subscribe frame. {@code "*"} collapses the whole union to
 * {@code ["*"]}, so {@code BuildSuccessfulListener} no longer appears on the wire — and keeps working
 * unchanged, because dispatch filters by signature and the wire set was only ever a filter, never a
 * promise.
 *
 * <h2>What {@link #onFrame} does, and what it deliberately does not</h2>
 *
 * <p>It enqueues and returns. The caller is the bus's websocket worker, delivering one frame at a
 * time to <em>every</em> consumer, and evaluation reads the git host once per candidate repository —
 * doing that here would stall the whole subscription behind it.
 *
 * <p>That hand-off is also why the frame's id is carried as <b>data</b>. {@code onFrame} runs inside
 * {@code CausationScope} of this frame's id, and that scope does not follow work onto another thread
 * — a plain {@code ThreadLocal} does not, deliberately. So {@link EventFrame#id()} travels on the
 * enqueued request, is written to {@code ci_run.trigger_event_id}, and comes back out at {@code
 * announceRun} to be passed to {@code publish(event, parent)} minutes later, on a different thread,
 * possibly after a restart. Note it is the frame's {@code id} and not its {@link
 * EventFrame#parentId()}: the arriving event is what causes this run, while its own parent is the
 * previous hop's business.
 */
@ApplicationScoped
public class CiEventTriggerListener implements QitsRawEventListener {

  @Inject CiEventTriggerService triggers;

  @Override
  public Set<String> signatures() {
    return Set.of(ALL);
  }

  @Override
  public void onFrame(EventFrame frame) {
    triggers.onEvent(
        new CiEventTriggerService.Arrival(
            frame.id(), frame.name(), frame.occurredAt(), frame.payload()));
  }
}
