-- A run row is now INSERTED AT ACCEPT TIME, carrying the new status QUEUED, and the worker flips it
-- to RUNNING when it dequeues it. Before this, a queued run was a closure on a single-threaded
-- executor and nothing else: invisible to every read surface and gone with the process. One widened
-- check constraint and no new columns. Appended to the lineage, never edited: V1, V2 and V3 are what
-- is already applied in every deployment.
--
-- V1 declared the status domain INLINE on the column -- `status varchar(32) not null check (status
-- in (...))` -- so the constraint is ANONYMOUS in the script and H2 named it itself. Measured on a
-- database created by V1 and on this platform's live ci database, that name is CONSTRAINT_76 in
-- both, because H2 derives it from the object counter and V1 is the same script everywhere. It is
-- still a generated name rather than one this repo chose, which is why the probe at the bottom
-- exists.
alter table ci_run drop constraint if exists CONSTRAINT_76;

-- The replacement is NAMED, so the next widening is one line with nothing to measure.
alter table ci_run add constraint ck_ci_run_status
    check (status in ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CONFIG_ERROR'));

-- THE GUARD, and it is the reason the drop above is allowed to name a generated constraint at all.
-- A database whose V1 check landed under some other generated name would take the drop as a no-op,
-- add the named constraint beside the old one, and then reject every single accepted run at insert
-- -- silently in every JVM test, loudly only in the deployment, which is this repo's worst failure
-- family (see "Schema changes" and the AUTO_SERVER note in AGENTS.md). So the migration writes one
-- QUEUED row and deletes it again: if the old constraint survived, this insert fails, Flyway fails
-- the migration, and the process refuses to start with the constraint named in the error. cd's
-- health gate keeps the previous container, so failing here is both loud and safe.
insert into ci_run
    (id, repo_id, branch, commit_sha, status, created_at, trigger_type, config_path)
values
    ('v4-status-probe', '__migration_probe__', 'main', '0', 'QUEUED',
     timestamp with time zone '1970-01-01 00:00:00Z', 'POST_RECEIVE', '__migration_probe__');
delete from ci_run where id = 'v4-status-probe';

-- The read behind GET /ci/api/runs/active, which is unscoped by repository -- the one query on this
-- surface that is not answered by idx_ci_run_repo_id.
create index idx_ci_run_status on ci_run (status);
