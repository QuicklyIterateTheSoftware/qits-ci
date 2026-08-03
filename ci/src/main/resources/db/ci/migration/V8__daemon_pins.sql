-- The daemon pin ladder (ci-daemon-autoadopt-plan.md, workstream BV): a durable, ordered list of
-- daemon versions qits-ci has seen released, each with a verdict from a container probe. Appended
-- to the lineage, never edited: V1-V7 are what is already applied in every deployment.
--
-- The configured qits.ci.daemon-version pin is NEVER a row here -- it is the ladder's bottom rung,
-- read straight from config, and it is never demoted. Only adopted candidates -- versions read off
-- a SoftwareRelease event for the daemon -- live in this table.
--
-- id is a generated row id, kept separate from version so the primary key never has to change
-- shape. version carries its own uniqueness: the download address is a plain {version}
-- substitution, so two rows naming the same version would be two conflicting answers to "what does
-- this version resolve to".
create table ci_daemon_pin (
    id varchar(255) not null primary key,
    version varchar(64) not null,
    source varchar(32) not null,
    verdict varchar(32) not null,
    event_id varchar(255) not null,
    occurred_at timestamp(6) with time zone not null,
    probed_at timestamp(6) with time zone,
    detail clob
);

alter table ci_daemon_pin add constraint uq_ci_daemon_pin_version unique (version);

-- NAMED from the start, unlike V1's status check -- see V4's lesson on generated constraint names
-- (AGENTS.md, "Schema changes"). The next widening of the verdict domain is one line with nothing
-- to measure.
alter table ci_daemon_pin add constraint ck_ci_daemon_pin_verdict
    check (verdict in ('UNPROVEN', 'PROVEN', 'REJECTED', 'UNKNOWN'));

-- Adoption looks up by the adopting event's id first (the idempotency key: a redelivered
-- SoftwareRelease is a no-op upsert, never a second row) and orders candidates by occurredAt -- the
-- event log's own ordering key, never a parsed calver -- to find the newest already-adopted
-- candidate.
create index idx_ci_daemon_pin_event_id on ci_daemon_pin (event_id);
create index idx_ci_daemon_pin_occurred_at on ci_daemon_pin (occurred_at);
