-- Cancellation is an outcome worth explaining. A manual cancellation records the caller-supplied
-- reason (or USER_CANCELLED), while queue deduplication records DEDUPED and the newer run that made
-- the queued one obsolete. The newer id is deliberately not a foreign key: an accepted run may be
-- discarded later when its commit has no CI configuration, and cancellation history must survive.
alter table ci_run add column cancellation_reason varchar(255);
alter table ci_run add column superseded_by_run_id varchar(255);

create index idx_ci_run_superseded_by on ci_run (superseded_by_run_id);
