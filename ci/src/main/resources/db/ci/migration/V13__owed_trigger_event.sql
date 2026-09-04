-- The trigger engine's owed-event ledger: an event that was ACCEPTED for evaluation and has not
-- been evaluated yet.
--
-- WHAT IT CLOSES, measured 2026-09-04. A durable bus listener's guarantee is exactly-once EFFECT:
-- the claim in `consumed_event` and the handler's own writes commit together or not at all. This
-- consumer could not hold up its end. `CiEventTriggerListener.onFrame` enqueues onto
-- `ci-trigger-worker` and returns, so the claim committed when the event was accepted rather than
-- when the run row existed — and the evaluation, which is a git-host fan-out, deliberately ran
-- outside the claiming transaction. A process that died in that gap left the event claimed, the
-- watermark past it, and no run: three release requests created within a second of a qits-ci
-- redeploy got no QA run at all, and with the release gate strictly requiring verdicts they hung
-- PENDING until they were withdrawn and recreated.
--
-- The claim lives on the eventstream datasource and a run row lives here, and ONE JTA transaction
-- does not take both (measured: `Enlisted connection used without active transaction`), so the two
-- cannot be made atomic. What can be made durable is the ACCEPTANCE: this row is written on ci's own
-- datasource, in its own transaction, BEFORE the accept is reported back to the funnel. So the
-- orderings are:
--
--   * die before this row commits  -> the claim rolls back with the handler; the bus re-offers.
--   * die after it and before the run -> the claim stands, and this row is what says the event was
--     never evaluated. The boot sweep (stop-first deployment: one CI process at a time) and the
--     periodic sweep re-evaluate it, and `unique (trigger_event_id, repo_id, config_path)` on
--     ci_run makes re-evaluating an event that DID record runs a no-op. That constraint is why
--     at-least-once evaluation is safe to build on here.
--
-- EMPTY IN A HEALTHY PROCESS, exactly like the eventstream outbox: the row is deleted the moment the
-- evaluation returns. A non-empty table is therefore a signal — events this instance accepted and
-- has not finished with — rather than a log of the bus, which qits-events already is.
create table ci_owed_event (
    -- The domain event's own id, which is also the dedupe identity every run it records carries.
    -- Primary key rather than a surrogate: one accepted event is one obligation, and a redelivery
    -- that reaches the accept again must find its own row rather than write a second.
    event_id varchar(255) not null primary key,
    event_name varchar(255) not null,
    occurred_at timestamp(6) with time zone not null,
    -- The canonical JSON qits-events stored, verbatim — the same string the evaluation selects on
    -- and the same one a step container is handed as $QITS_EVENT_PAYLOAD. Nullable because an event
    -- with no payload is the funnel's business to refuse, not this table's.
    payload text,
    accepted_at timestamp(6) with time zone not null
);

-- The sweeps' only read: everything accepted before a cutoff. The boot sweep passes `now` (nothing
-- else is running — the deployment is stop-first), the periodic one passes now minus the grace, so a
-- row this process is still working on is never re-offered to itself.
create index idx_ci_owed_event_accepted on ci_owed_event (accepted_at);
