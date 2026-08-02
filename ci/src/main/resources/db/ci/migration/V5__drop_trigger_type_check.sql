-- H2 2.4.240 keeps a checked IN-set tied to the session that compiled it. Once the pool retires
-- that session, a later update can fail with 23514 "Check constraint invalid" even though the
-- value is unchanged and valid. This was observed during a clean local bootstrap after three runs:
-- qits-workspaces finished its step, then updating the run row failed on this constraint and left
-- the run permanently RUNNING. Java's TriggerType enum already owns this invariant at every write.
-- Keep the unique causation constraint; only duplicated enum checks are removed.
alter table ci_run drop constraint if exists ck_ci_run_trigger_type;
alter table ci_run drop constraint if exists ck_ci_run_status;
alter table ci_step drop constraint if exists ck_ci_step_status;
