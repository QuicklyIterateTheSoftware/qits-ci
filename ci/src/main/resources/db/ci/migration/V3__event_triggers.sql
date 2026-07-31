-- A run can now be caused by a DOMAIN EVENT as well as by a push: a repository commits
-- .config/qits/ci-event-<name>.yml declaring an event name and a selection over its payload, and a
-- matching event runs that file's pipeline against the head of main. Four provenance columns and one
-- unique constraint. Appended to the lineage, never edited: V1 and V2 are what is already applied in
-- every deployment.
--
-- trigger_type says which trigger produced the row. It is added WITH A DEFAULT so that every
-- existing row is backfilled POST_RECEIVE — which is what they all were — and the default is then
-- dropped, because a run that reaches the insert without saying how it was triggered is a bug this
-- schema should not paper over.
alter table ci_run add column trigger_type varchar(32) default 'POST_RECEIVE' not null;
alter table ci_run alter column trigger_type drop default;
alter table ci_run add constraint ck_ci_run_trigger_type
    check (trigger_type in ('POST_RECEIVE', 'EVENT'));

-- The event that caused the run, and its name beside it so the row reads without a join into
-- another service. Null on every post-receive run: a push is not caused by an event.
--
-- trigger_event_id is also what the run's OWN BuildSuccessful is published under as parentId. The
-- engine consumes the frame on the bus's dispatch thread and enqueues the run, which executes later
-- on ci-run-worker, so no thread-local can carry the cause across; this column is the durable form
-- of that edge and the only one that survives a restart.
alter table ci_run add column trigger_event_id varchar(255);
alter table ci_run add column trigger_event_name varchar(255);

-- Which committed file declared the pipeline. Post-receive rows are backfilled with the constant
-- they have always used, by the same add-default-then-drop shape as trigger_type above.
alter table ci_run add column config_path varchar(512)
    default '.config/qits/ci-post-receive.yml' not null;
alter table ci_run alter column config_path drop default;

-- THE at-most-one guarantee, and it is a database constraint rather than an application check
-- because the thing it must survive is a race and a restart. A second arrival of the same event —
-- bus replays are legal, and the future catch-up feature will redeliver on purpose — hits this and
-- is dropped as already-triggered, not re-run. One event, one trigger file, at most one run, ever.
--
-- NULL trigger_event_id must NOT trip it, or every post-receive run after the first would fail to
-- insert. SQL's rule is that rows are duplicates only when all corresponding values are non-null and
-- equal, so (null, 'repo', '.config/qits/ci-post-receive.yml') is distinct from itself; H2 follows
-- it for the multi-column form. That is verified by a test rather than believed — see
-- CiEventTriggerDedupeTest — because it is the one line here whose failure mode is "every push stops
-- recording a run".
alter table ci_run add constraint uq_ci_run_event_trigger
    unique (trigger_event_id, repo_id, config_path);

-- The read the engine's pre-check makes, and the read a future causation walk will make.
create index idx_ci_run_trigger_event_id on ci_run (trigger_event_id);
