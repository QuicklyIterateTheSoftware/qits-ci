-- The owed announcements written before V10 and V11 get the repository identity they will need.
--
-- V10 added project_id and V11 added repo_name to ci_release_announcement, both "nullable, no
-- default and no backfill", and both said why: a run answered id-addressed has no project and no
-- public name, so absent is an ordinary value and there is nothing to fill an existing row with.
-- That reasoning is right about ANNOUNCED rows and wrong about OWED ones, and the difference is the
-- whole of this migration.
--
-- An announced row is a record of what was published. Rewriting it would make the table disagree
-- with the event log, and no consumer re-reads it. An OWED row is an obligation that has not been
-- met yet: it is the payload of a SoftwareRelease that has not been published, and it will be
-- published — by an SCMRelease arriving later, or by ReleaseJoin's boot sweep, in a process that
-- cannot read the run row back (which is exactly why V10 and V11 copy these columns onto the
-- obligation in the first place). An owed row written before those columns existed therefore
-- carries nulls that were never a statement about the repository; they are the absence of a column.
-- Announced as-is, it reaches qits-deployments with no name pair, which is the address the
-- storage-client guard 403s everyone but qits-projects on — V11's own header describes that failure.
--
-- This instance has owed rows: the release join's startup sweep logs "0 of 7 owed release(s) had an
-- SCMRelease" on every boot. Those seven keys are bootstrap replays whose tag was restored and whose
-- release never happened, which is the designed forever case — but "forever" is not a guarantee, and
-- a real SCMRelease for any of those pairs would announce them name-less today.
--
-- So: owed rows only, and only where the run row still knows the answer. A run that was itself
-- id-addressed leaves the nulls exactly as they are, because there the null IS the statement.
-- Idempotent by construction — the second run matches nothing, since every row it could match now
-- has a value — and safe on a fresh database, where it updates zero rows.
update ci_release_announcement a
   set repo_name = coalesce(a.repo_name, r.repo_name),
       project_id = coalesce(a.project_id, r.project_id)
  from ci_run r
 where r.id = a.run_id
   and a.announced_at is null
   and (a.repo_name is null and r.repo_name is not null
        or a.project_id is null and r.project_id is not null);
