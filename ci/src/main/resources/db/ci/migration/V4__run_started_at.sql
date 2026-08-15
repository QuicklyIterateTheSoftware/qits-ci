-- Acceptance and execution are separate lifecycle moments: created_at is queue entry, while
-- started_at is stamped when the worker claims the row. Existing completed history cannot recover
-- that instant honestly, so it remains null.
alter table ci_run add column started_at timestamp(6) with time zone;
