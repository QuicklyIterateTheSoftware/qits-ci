-- The public coordinate of the repository a run belongs to: (project_id, repo_name), which after
-- the identity cutover is the ONE address anything above the projects↔githost seam speaks —
-- /git/<projectId>/<repoName>. repo_id stays exactly what it is (the storage key, an opaque UUID
-- afterwards) because it is what the dedupe constraint and every historical row are built on.
--
-- Both nullable, with no backfill and no default. The git host fills them from the address a push
-- arrived on, so an id-addressed push announces without them and no history has them; a run with
-- neither builds id-addressed URLs, which is what this service did before names existed. Inventing
-- a value here would claim an address nobody pushed to.
alter table ci_run add column project_id varchar(255);
alter table ci_run add column repo_name varchar(255);
