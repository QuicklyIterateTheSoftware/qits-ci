package eu.wohlben.qits.ci.bus;

import eu.wohlben.qits.ci.control.CiEventTriggerService;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.QitsRawEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The bus end of the trigger engine: every frame, handed to {@link CiEventTriggerService} as four
 * plain strings.
 *
 * <p>It is the third bean in this package and the same shape as the other two — {@code
 * BuildSuccessfulAnnouncer} adapts a {@code ci/control} port outward, this adapts the bus inward, and
 * both exist so the {@code ci} module never imports {@code eu.wohlben.qits.eventstream}. Registration
 * is "be a bean": {@code EventDispatcher} injects {@code Instance<QitsDurableEventListener>}, which
 * is what ArC counts as a use, so no {@code @Unremovable} and no annotation beyond the scope.
 *
 * <h2>Durable, because a missed event is a build that never runs</h2>
 *
 * <p>This was a {@link QitsRawEventListener} — live-only and at-most-once, so a frame broadcast
 * while this process was disconnected, restarting or mid-cutover was gone. For a trigger engine that
 * is a release train that silently stops, and the 2026-08-10 rebootstrap campaign measured exactly
 * that. As a {@link QitsDurableEventListener} the same events arrive on the same {@code onFrame},
 * plus whatever the catch-up sweep reads back out of the log.
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
 * <h2>{@link #selects} is the default, and that is a decision with a price</h2>
 *
 * <p>The seam asks a durable listener to narrow with a <b>pure, cheap predicate</b>, and to store
 * only what it selects. This engine's selection is neither: deciding whether an event matches
 * anything means listing {@code .config/qits/} in every candidate repository and parsing each trigger
 * file — an HTTP fan-out at the git host, on results that change with every push. Answering it here
 * would put that fan-out in front of the claim, on the dispatch thread, and a {@code selects} that
 * throws leaves the event owed, so one unreachable git host would wedge the watermark.
 *
 * <p>So this listener selects everything it is subscribed to, which is everything, and the price is
 * that <b>every event on the bus leaves a claim row</b> for this consumer. Bounded rather than
 * unbounded: the sweeper prunes claims the watermark has left behind by more than {@code
 * qits.eventstream.prune-horizon}, so the table holds the stream/catch-up overlap window and not a
 * copy of the log. The engine's real selection stays where it can afford to be wrong — inside {@code
 * CiEventTriggerService}, per candidate repository, with per-repository containment.
 *
 * <h2>What {@link #onFrame} does, and the window it leaves open</h2>
 *
 * <p>It enqueues and returns. The caller is the bus's websocket worker (or the catch-up sweeper),
 * and evaluation reads the git host once per candidate repository — doing that here would stall the
 * whole subscription behind it, and it would hold the claiming transaction open across an HTTP
 * fan-out.
 *
 * <p><b>State the cost plainly: the claim commits when the event is ACCEPTED for evaluation, not
 * when the run row exists.</b> A crash in the gap between the two loses that event, which is a
 * narrower guarantee than the seam's "exactly-once effect" and is the deliberate trade for keeping
 * the fan-out off the dispatch thread. Everything either side of the gap is covered: a full queue is
 * a failure rather than a shrug (below), and an accepted run becomes a {@code QUEUED} row that
 * survives a restart on its own.
 *
 * <p><b>Duplicate delivery is now settled twice over, and both nets stay.</b> The claim row makes one
 * event reach {@code onEvent} at most once whatever mix of live frame and catch-up row produced the
 * arrivals; underneath it, {@code ci_run}'s {@code unique (trigger_event_id, repo_id, config_path)}
 * still refuses a second run for the same event and trigger file. The constraint is what survives a
 * race between two evaluations and a restart mid-evaluation, which a claim written on another
 * datasource cannot, so removing it would be trading a guarantee for a tidier diagram.
 *
 * <p><b>A full queue throws, and that is the point of the return value.</b> The engine answers
 * whether it accepted the event; {@code false} means it was not evaluated, which is a statement about
 * this process being busy and not about the event, so the right answer is to fail and leave it owed.
 * The next sweep offers it again. The one thing swallowed here is a frame with <b>no name</b>: no
 * trigger file can ever declare a nameless event, so retrying it forever would wedge this listener's
 * watermark on an event that can never work — the poison case the seam tells a handler to swallow
 * with a WARN.
 *
 * <h2>Late delivery needs no tip check here</h2>
 *
 * <p>Catch-up delivers out of order, and a handler whose effect is last-writer-wins has to collapse
 * for itself. This one's is not: it appends a run per (event, repository, trigger file). A trigger
 * without {@code checkout:} builds the head of {@code main}, so a minutes-old event evaluated now
 * runs today's head — which is what such a trigger means, and not a write over anything a newer
 * event did. A trigger <b>with</b> {@code checkout:} builds the commit its event names, so a late
 * event builds an old commit — equally correct (the run says something true about that commit),
 * and the burst case is collapsed downstream by the accept-time branch supersede, which this
 * listener neither knows nor needs to.
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
public class CiEventTriggerListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(CiEventTriggerListener.class);

  /**
   * The storage key, and therefore not a label: it names every {@code consumed_event} row and the
   * {@code consumer_watermark} this engine keeps. It survives a rename of this class, and it is never
   * handed to a listener that means something else — a consumer inheriting it would inherit a
   * watermark saying it had already seen events it has never been offered.
   */
  static final String CONSUMER_ID = "ci-event-triggers";

  @Inject CiEventTriggerService triggers;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(QitsRawEventListener.ALL);
  }

  @Override
  public void onFrame(EventFrame frame) {
    if (frame.name() == null || frame.name().isBlank()) {
      // Poison, not a hiccup: a trigger file declares an event by name, so a nameless event can
      // never match one. Swallowed with a WARN because throwing would offer it again forever and
      // hold this listener's watermark behind it.
      LOG.warnf("Event %s arrived with no name; no trigger file can ever declare it", frame.id());
      return;
    }
    boolean accepted =
        triggers.onEvent(
            new CiEventTriggerService.Arrival(
                frame.id(), frame.name(), frame.occurredAt(), frame.payload()));
    if (!accepted) {
      // Retryable: the queue is full, so this event was not evaluated. Failing rolls the claim back
      // and the next catch-up sweep offers it again, which is the whole reason the engine answers
      // rather than shrugging.
      throw new IllegalStateException(
          "the trigger evaluation queue is full; event " + frame.id() + " stays owed");
    }
  }
}
