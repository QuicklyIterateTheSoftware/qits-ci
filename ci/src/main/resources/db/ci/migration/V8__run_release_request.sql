-- Which release request this run serves, and therefore which fold its verdict is about.
--
-- qits-projects maintains one backing branch per open release request — `release/<id>`, an octopus
-- merge of N sources refolded on every change — and announces each successful fold as
-- `ReleaseRequestChanged`. A repository's QA pipeline runs off that event, at the merged sha, and
-- the verdict comes back as the ordinary BuildSuccessful/BuildFailed keyed on (repoId, commitSha).
-- What that key cannot say is WHICH REQUEST the run belongs to: the sha is a fold nobody pushed and
-- is rewritten by the next refold, so it is not a handle a cancellation or a retry can hold. The id
-- is.
--
-- Nullable, no backfill, no default and part of no constraint. Null is the ordinary value — every
-- push run and every event run not triggered by a release request — so there is nothing for an
-- existing row to be filled with and no reading of "absent" to get wrong. The dedupe stays
-- (trigger_event_id, repo_id, config_path) exactly as it is; nothing here is a second key.
alter table ci_run add column release_request_id varchar(255);

-- The read a later cancellation/retry makes: every run of one release request, newest first through
-- the existing created_at ordering. Partial, because the column is null on the overwhelming majority
-- of rows and an index over those nulls would be a second copy of the table for no query.
create index idx_ci_run_release_request_id on ci_run (release_request_id)
    where release_request_id is not null;
