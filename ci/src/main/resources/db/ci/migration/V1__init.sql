-- The whole schema of qits-ci, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE. The H2 lineage (V1-V8 plus the java V9) is deleted rather than
-- continued, and the move off H2 is what allowed it: this context's store becomes a postgres
-- database provisioned by the deployment spec's `resources:` line, reached by re-bootstrap rather
-- than by a data migration, so no postgres database anywhere has ever run those files and no
-- `V10__move_to_postgres.sql` had a reader. Their content is history in this repository's log; the
-- shape below is where the nine of them ARRIVED, translated. FROM HERE ON THE ORDINARY RULE IS
-- BACK: keep appending, never edit an applied migration.
--
-- What the H2 lineage cost, and what the translation therefore does NOT reproduce:
--   * V2/V3/V6/V7's `alter table add column` steps are folded into the create statements, with no
--     backfill and no add-default-then-drop dance, because every database reaching this file is
--     empty. `trigger_type` and `config_path` are plainly `not null` here; V3 had to arrive at that
--     through a default it then dropped.
--   * V4's probe row is gone. It inserted and deleted one QUEUED run to prove a generated
--     constraint had really been dropped — a check on the migration ABOVE it, not on this schema.
--   * `clob` becomes `text` (see the entity note below), and every timestamp is
--     `timestamp(6) with time zone`, which postgres reads as its own timestamptz. V6's three
--     columns said `timestamp with time zone` without the precision; they are spelled like their
--     siblings here, and postgres' default precision is 6 either way.
--
-- CHECK CONSTRAINTS, DECIDED ONE AT A TIME rather than by a rule, because postgres would now permit
-- every one of them:
--   * ci_run.status, ci_run.trigger_type, ci_step.status — NOT RECREATED. V5 and V9 removed them
--     from H2 because H2 2.4.240 tied a compiled IN-set to the session that made it and failed
--     valid writes with 23514 once the pool retired that session; on a freshly bootstrapped
--     platform that killed every run, step-less. Postgres has no such defect, so the H2 reason has
--     lapsed and the OTHER reason is what stands: these are catalogues that grow. `status` was
--     widened once already (V4 added QUEUED) and `trigger_type` gained EVENT after POST_RECEIVE, so
--     a database enumerating them would be a second list to keep in step with CiRunStatus,
--     CiTriggerType and CiStepStatus — which are @Enumerated(STRING) and are what every write goes
--     through. Same conclusion, and the same wording, as qits-platform-deployments' own V1.
--   * ci_daemon_pin.verdict — KEPT, and it is the one that reads the other way, exactly as the
--     eventstream outbox's `status` check does. V8 declared it NAMED and V9 deliberately left it
--     alone rather than sweeping it up. A verdict is a closed statement about one probe's outcome
--     with an UNKNOWN arm already in it, so it is an invariant rather than a growing catalogue; and
--     being named, the day it ever needs widening costs one line of SQL with nothing to measure.
--     That is precisely what V1's two anonymous inline checks could not offer, and why they became
--     a java migration reading INFORMATION_SCHEMA.
--
-- No column here holds another context's key: repo_id, trigger_event_id and superseded_by_run_id are
-- plain strings. The one FK is inside this context's own database, which the cross-context rule
-- permits.

-- --- runs -----------------------------------------------------------------------------------------

-- One ci_run per accepted trigger: a push whose commit carried .config/qits/ci-post-receive.yml, or
-- a domain event matching one of the repository's .config/qits/ci-event-<name>.yml files. The row is
-- born at ACCEPT time carrying QUEUED and the worker flips it to RUNNING, so a queued run is
-- visible on every read surface and survives the process.
create table ci_run (
    id varchar(255) not null primary key,
    -- A repository id from another context, as a plain string. A deleted repository leaves its runs
    -- behind as dangling history, which is the whole point of the separate database.
    repo_id varchar(255) not null,
    branch varchar(255) not null,
    commit_sha varchar(64) not null,
    -- QUEUED, RUNNING, SUCCESS, FAILED or CONFIG_ERROR. Not checked here; see the header.
    status varchar(32) not null,
    created_at timestamp(6) with time zone not null,
    finished_at timestamp(6) with time zone,
    -- The daemon build this run is pinned to, resolved once when the run is created and injected
    -- into every one of its step containers, so a deploy landing mid-run cannot make step 3 speak a
    -- different protocol than step 1. Null on a CONFIG_ERROR run that never launched a container.
    daemon_version varchar(64),
    -- POST_RECEIVE or EVENT. Not checked here; see the header.
    trigger_type varchar(32) not null,
    -- The event that caused the run, and its name beside it so the row reads without a join into
    -- another service. Null on every post-receive run: a push is not caused by an event.
    --
    -- trigger_event_id is also what the run's OWN BuildSuccessful is published under as parentId.
    -- The engine consumes the frame on the bus's dispatch thread and enqueues the run, which
    -- executes later on ci-run-worker, so no thread-local can carry the cause across; this column is
    -- the durable form of that edge and the only one that survives a restart.
    trigger_event_id varchar(255),
    trigger_event_name varchar(255),
    -- Which committed file declared the pipeline: the post-receive path on a push, the matching
    -- ci-event-*.yml on an event. Identity rather than description — two trigger files in one
    -- repository matching one event are two runs by design.
    config_path varchar(512) not null,
    -- Everything an event-triggered run's worker needs, made durable at accept time. Before these
    -- existed the payload and the parsed pipeline lived in an executor closure, so restarting
    -- qits-ci had to delete an accepted QUEUED run.
    trigger_event_occurred_at timestamp(6) with time zone,
    -- `text`, and the entity says columnDefinition = "text" rather than @Lob. That is the one
    -- mapping the move had to change: on H2 a @Lob String was a clob and the two agreed; on postgres
    -- @Lob means a LARGE OBJECT — Hibernate binds an oid and the insert fails against a text column.
    -- Unbounded either way, which is what a verbatim payload needs.
    trigger_event_payload text,
    trigger_config text,
    -- Why a run was cancelled, and — for a queue dedupe — the newer run that made it obsolete.
    -- Deliberately not a foreign key: an accepted run may be discarded later when its commit has no
    -- CI configuration, and cancellation history must survive that.
    cancellation_reason varchar(255),
    superseded_by_run_id varchar(255)
);

-- THE at-most-one guarantee, and it is a database constraint rather than an application check
-- because what it must survive is a race and a restart. A second arrival of the same event — bus
-- replays are legal, and the future catch-up feature will redeliver on purpose — hits this and is
-- dropped as already-triggered, not re-run. One event, one trigger file, at most one run, ever.
--
-- NULL trigger_event_id must NOT trip it, or every post-receive run after the first would fail to
-- insert. SQL's rule is that rows are duplicates only when all corresponding values are non-null and
-- equal, so (null, 'repo', '.config/qits/ci-post-receive.yml') is distinct from itself; postgres
-- follows it, and plain `unique` is therefore right — `nulls not distinct`, which postgres 15 added
-- and this engine has, is exactly what must NOT be asked for here. Verified by a test rather than
-- believed — see CiEventTriggerDedupeTest — because it is the one line here whose failure mode is
-- "every push stops recording a run".
alter table ci_run add constraint uq_ci_run_event_trigger
    unique (trigger_event_id, repo_id, config_path);

create index idx_ci_run_repo_id on ci_run (repo_id);
create index idx_ci_run_created_at on ci_run (created_at);
-- The read behind GET /ci/api/runs/active, which is unscoped by repository — the one query on that
-- surface idx_ci_run_repo_id does not answer.
create index idx_ci_run_status on ci_run (status);
-- The trigger engine's pre-check, and the read a future causation walk will make.
create index idx_ci_run_trigger_event_id on ci_run (trigger_event_id);
create index idx_ci_run_superseded_by on ci_run (superseded_by_run_id);

-- --- steps ----------------------------------------------------------------------------------------

-- One row per step of a run, in declaration order. A row is WRITTEN ONCE, ALREADY TERMINAL: while a
-- step runs it has no row at all and the live output is the in-memory relay, so the database never
-- holds a half-written step and a crash mid-run cannot leave one claiming to still execute.
create table ci_step (
    id varchar(255) not null primary key,
    run_id varchar(255) not null,
    step_index int not null,
    image varchar(512) not null,
    -- PENDING, RUNNING, SUCCESS, FAILED or SKIPPED. Not checked here; see the header. PENDING and
    -- RUNNING are never written any more — the row appears terminal — but the startup sweep still
    -- has to be able to read them.
    status varchar(32) not null,
    exit_code int,
    -- Both HOST-stamped: started_at is when qits-ci sent the step to the container's daemon,
    -- finished_at is when the terminal frame came back or a deadline fired instead. Neither is ever
    -- what the container claimed — it is running repo-controlled code by then, and a clock is the
    -- cheapest thing to forge.
    started_at timestamp(6) with time zone,
    finished_at timestamp(6) with time zone,
    -- The step's combined output, bounded and tail-truncated while it arrives
    -- (qits.ci.output-max-chars). `text` for the same reason as ci_run's two columns above.
    output text
);

create index idx_ci_step_run_id on ci_step (run_id);

-- An FK inside ci's own database is fine — the "plain strings, never a foreign key" rule is about
-- keys belonging to another context.
alter table ci_step add constraint fk_ci_step_run foreign key (run_id) references ci_run;

-- --- the daemon pin ladder --------------------------------------------------------------------

-- A durable, ordered list of qits-ci-daemon versions this instance has seen released, each with a
-- verdict from a container probe. The CONFIGURED qits.ci.daemon-version pin is never a row here: it
-- is the ladder's bottom rung, read straight from config and never demoted. Only adopted
-- candidates — versions read off a SoftwareRelease event for the daemon — live in this table.
--
-- id is a generated row id, kept separate from version so the primary key never has to change
-- shape. version carries its own uniqueness: the download address is a plain {version}
-- substitution, so two rows naming the same version would be two conflicting answers to what it
-- resolves to.
create table ci_daemon_pin (
    id varchar(255) not null primary key,
    version varchar(64) not null,
    source varchar(32) not null,
    verdict varchar(32) not null,
    event_id varchar(255) not null,
    occurred_at timestamp(6) with time zone not null,
    probed_at timestamp(6) with time zone,
    detail text
);

alter table ci_daemon_pin add constraint uq_ci_daemon_pin_version unique (version);

-- The one check constraint this schema keeps, and the header says why: a closed verdict domain with
-- its own UNKNOWN arm, NAMED so a widening is one line.
alter table ci_daemon_pin add constraint ck_ci_daemon_pin_verdict
    check (verdict in ('UNPROVEN', 'PROVEN', 'REJECTED', 'UNKNOWN'));

-- Adoption looks up by the adopting event's id first (the idempotency key: a redelivered
-- SoftwareRelease is a no-op upsert, never a second row) and orders candidates by occurred_at — the
-- event log's own ordering key, never a parsed calver — to find the newest already-adopted
-- candidate.
create index idx_ci_daemon_pin_event_id on ci_daemon_pin (event_id);
create index idx_ci_daemon_pin_occurred_at on ci_daemon_pin (occurred_at);
