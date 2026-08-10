-- The platform's generic causation column (qits-eventstream's CausedRow): the id of the event a
-- run row was written because of, stamped from the ambient CausationScope at persist. Nullable —
-- a post-receive run has no cause — and never a foreign key: the event lives in qits-events'
-- store, the same reason trigger_event_id above it is a bare string. No backfill: trigger_event_id
-- keeps the history this column starts recording from here on, and for every event run the two
-- agree by construction.
alter table ci_run add column causation_id uuid;
