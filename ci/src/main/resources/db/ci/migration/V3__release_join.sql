-- The release join: a SoftwareRelease is announced only for a REAL release.
--
-- Until now a green release-recipe run announced one SoftwareRelease per `artifacts:` entry off ANY
-- trigger. A bootstrap replay pushes the release tag without releasing anything, so the replay
-- impersonated a release and woke the release train against a half-deployed platform. The
-- discriminator is in the event vocabulary already: a real release produces BOTH the tag push and an
-- SCMRelease (which only qits-workspaces publishes); a replay produces only the tag.
--
-- So the announcement now needs two facts, and the two race on a real release — the tag build can
-- finish before or after the SCMRelease arrives. These two tables are the halves of that join, each
-- durable on its own, so whichever fact lands second finds the first waiting for it and a restart in
-- between costs nothing.
--
-- The join key is (repository, version). ci_release_announcement takes it from the run — repo_id is
-- the repository whose pipeline published, version is read out of the triggering event (`version` on
-- an SCMRelease, `tagName` on an SCMPublishTag, which IS the version string). ci_scm_release takes it
-- from the SCMRelease payload.

-- --- what a green release pipeline owes ------------------------------------------------------------

-- One row per (green run, declared artifact): the announcement that run owes, made the moment the
-- release fact is in and never before. announced_at null is the whole of "still owed" — the row stays
-- afterwards as the record that the announcement was made and when.
--
-- A run whose release fact never arrives keeps its owed rows forever, by design: no timeout and no
-- fallback, because a replay has no novelty to announce and inventing one is the defect this table
-- exists to close. The rows are then the readable account of what published without being released.
create table ci_release_announcement (
    id varchar(255) not null primary key,
    -- The run that published it. Deliberately NOT a foreign key, for the reason
    -- superseded_by_run_id is not one either: this row is an obligation that must outlive whatever
    -- happens to the run row, and a cascade is a way to lose an announcement.
    run_id varchar(255) not null,
    -- The repository whose pipeline published — this repo, not the upstream that triggered it.
    repo_id varchar(255) not null,
    version varchar(255) not null,
    -- npm, maven, docker or daemon: CiArtifact.Type#declared(), the keyword the trigger file used,
    -- which is also the wire value. Not checked here — it is a catalogue that grows, and
    -- CiArtifact.Type is what every write goes through. See V1's header.
    package_type varchar(32) not null,
    package_name varchar(512) not null,
    -- Where this artifact sat in the trigger file's `artifacts:` list. Carried so the announcements
    -- go out in the order the repository declared them: the rows are written in one instant, so
    -- created_at cannot order them and a row id is a random UUID.
    artifact_index int not null,
    -- The run's terminal timestamp, which is when the artifact became available. Carried rather than
    -- re-derived: the announcement may be made minutes later, and the event wants the moment the
    -- package appeared rather than the moment the join closed.
    finished_at timestamp(6) with time zone not null,
    -- The event that caused the run, stamped as the published event's parent. Null on a run nothing
    -- announced (CiRunService.execute, the test entry).
    trigger_event_id varchar(255),
    created_at timestamp(6) with time zone not null,
    announced_at timestamp(6) with time zone
);

-- One run announces one artifact once. The join drives an owed row from two directions — the green
-- run itself and a later SCMRelease — and a boot sweep drives it from a third, so what keeps a
-- redelivery or a restart from owing the same announcement twice has to be a constraint rather than a
-- check: it is the only net that survives a race between the run worker and the bus's dispatch
-- thread.
alter table ci_release_announcement add constraint uq_ci_release_announcement_artifact
    unique (run_id, package_type, package_name);

-- The join's own read: the owed rows for one (repository, version).
create index idx_ci_release_announcement_owed on ci_release_announcement (repo_id, version);

-- --- the release fact -------------------------------------------------------------------------------

-- One row per SCMRelease this instance has seen: the durable half that says a (repository, version)
-- is a real release. It is kept forever rather than pruned — a green run may arrive arbitrarily
-- later, and a fact that expired would turn a slow build into a silent replay.
create table ci_scm_release (
    id varchar(255) not null primary key,
    -- The repository that released, by the coordinate the event carries. repo_name is the same
    -- repository under its registered name, which SCMRelease carries beside the id and may be null;
    -- the lookup matches either, because a run's repo_id is the git host's id and the two spellings
    -- agree on this platform but are not promised to.
    repo_id varchar(255) not null,
    repo_name varchar(255),
    version varchar(255) not null,
    -- The announcing SCMRelease's own id, and its timestamp. Kept so a row says which event made the
    -- claim; the durable consumer's claim ledger is what makes one event reach the handler once.
    event_id varchar(255) not null,
    occurred_at timestamp(6) with time zone not null,
    seen_at timestamp(6) with time zone not null
);

-- One release, one row. The insert is guarded by a read first, so this constraint is what survives
-- the race between the live frame and a catch-up sweep offering the same release to two threads.
alter table ci_scm_release add constraint uq_ci_scm_release_repo_version
    unique (repo_id, version);

-- The second spelling of the same lookup — see repo_name above.
create index idx_ci_scm_release_repo_name on ci_scm_release (repo_name, version);
