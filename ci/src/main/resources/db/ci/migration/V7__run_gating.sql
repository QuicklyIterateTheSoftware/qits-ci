-- Whether a red outcome of this run should stand in the way of releasing its commit — the data
-- form of the "userflows are non-gating" convention, for the release-quality-gates build gate.
-- True for every push run; an event run takes it from its trigger file's `gating:` key (absent is
-- true, and only the userflow pipelines say false).
--
-- Added with a default so every existing row fills as gating — every historical run predates the
-- distinction and gating is the conservative reading — then the default is dropped, so a future
-- insert that forgets the value fails loudly rather than getting a silent one (the V3-era lesson;
-- the entity initializes the field to true, so nothing here relies on a column default).
alter table ci_run add column gating boolean not null default true;
alter table ci_run alter column gating drop default;
