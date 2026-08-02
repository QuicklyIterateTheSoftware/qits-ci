-- Event-triggered runs must carry everything their worker needs. Before this migration only the
-- event id/name and selected commit were durable; the payload and parsed pipeline lived in an
-- executor closure, so restarting qits-ci had to delete an accepted QUEUED run.
alter table ci_run add column trigger_event_occurred_at timestamp with time zone;
alter table ci_run add column trigger_event_payload clob;
alter table ci_run add column trigger_config clob;

-- Existing EVENT rows cannot be reconstructed because the lost values were never stored. They are
-- historical terminal rows in normal deployments; nullable columns preserve that history. Every
-- newly accepted EVENT row writes all three values atomically with the run.
