-- Which run this one re-fires by hand, and therefore how a retry gets past the dedupe.
--
-- A manual retry asks for the SAME work again: the same repository, the same trigger file, the same
-- checkout, the same release request. Everything the engine keys a run by is therefore identical to
-- the run being retried, and one of those keys is a guarantee — `unique (trigger_event_id, repo_id,
-- config_path)` is the at-most-one-run-per-(event, trigger file) contract, and a second row carrying
-- the original event id would be exactly the replay it exists to refuse.
--
-- So a retry carries its own SYNTHETIC trigger identity — `retry:<its own run id>`, minted in
-- CiRunService.insertRetry — which is unique by construction and impersonates no event qits-events
-- ever minted. The constraint is untouched: it keeps meaning what it meant, and it is the retry that
-- is addressed differently rather than the rule that is weakened. What would have weakened it is the
-- tempting alternative, adding this column to the constraint: it is null on every ordinary row, and
-- SQL treats rows with a null in the tuple as never colliding, so the dedupe would have stopped
-- firing for every run on the platform.
--
-- This column is then the provenance the synthetic id cannot carry: which run a person re-fired.
-- Nullable, no backfill, no default, part of no constraint — null is the ordinary value, and there
-- is nothing for an existing row to be filled with. The causation edge is unaffected: a retry copies
-- `causation_id` from the run it re-fires, so the events it publishes still name the domain event
-- that started the whole thing rather than a root of their own.
alter table ci_run add column retry_of_run_id varchar(255);

-- "What did this retry re-fire" is answered off the row itself; this index answers the other
-- direction — "was this run ever retried" — which the run view asks once per page. Partial, for
-- V8's reason: the column is null on the overwhelming majority of rows and an index over those nulls
-- would be a second copy of the table for no query.
create index idx_ci_run_retry_of_run_id on ci_run (retry_of_run_id)
    where retry_of_run_id is not null;
