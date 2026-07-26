-- The CI pipeline schema (docs/epics/qits-ci/): one ci_run per (push, updated branch ref) whose
-- pushed commit carried .config/qits/ci-post-receive.yml, with its ordered ci_step children.
-- repo_id is a plain string — NO FK into qits' tables (separate physical DB; extraction-ready).

create table ci_run (
    id varchar(255) not null primary key,
    repo_id varchar(255) not null,
    branch varchar(255) not null,
    commit_sha varchar(64) not null,
    status varchar(32) not null check (status in ('RUNNING', 'SUCCESS', 'FAILED', 'CONFIG_ERROR')),
    created_at timestamp(6) with time zone not null,
    finished_at timestamp(6) with time zone
);

create table ci_step (
    id varchar(255) not null primary key,
    run_id varchar(255) not null,
    step_index int not null,
    image varchar(512) not null,
    status varchar(32) not null check (status in ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    exit_code int,
    output clob
);

create index idx_ci_run_repo_id on ci_run (repo_id);
create index idx_ci_run_created_at on ci_run (created_at);
create index idx_ci_step_run_id on ci_step (run_id);

-- FK inside ci's own DB is fine — the "string ids, never FK" rule is about qits' tables.
alter table ci_step add constraint fk_ci_step_run foreign key (run_id) references ci_run;
