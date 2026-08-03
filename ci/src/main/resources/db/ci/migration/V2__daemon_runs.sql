-- Step execution moved from `docker run <script>` to a qits-ci-daemon inside each step container,
-- which brings three columns and no rewrite of the existing ones. Appended to the lineage, never
-- edited: V1 is what is already applied in every deployment.
--
-- daemon_version pins the run to one daemon build. It is resolved once when the run is created and
-- injected into every one of that run's step containers, so a deploy landing mid-run cannot make
-- step 3 speak a different protocol than step 1 — and the row records afterwards which binary
-- produced its results. Null on runs recorded before this existed, and on a CONFIG_ERROR run that
-- never launched a container. It holds whatever the daemon pin ladder answered for this run (see
-- ci/control/CiDaemonPins) -- a readable calver for an adopted or configured release, or the sha256
-- hex a platform bootstrapped before it had a calver row.
alter table ci_run add column daemon_version varchar(64);

-- Per-step timestamps. Both are HOST-stamped: started_at is when qits-ci sent the step's script to
-- the container's daemon, finished_at is when the terminal frame came back (or when a deadline fired
-- instead). Neither is ever what the container claimed — a daemon is running repo-controlled code by
-- then, and a clock is the cheapest thing to forge. Null on a step that never started, on a SKIPPED
-- step, and on every row written before this migration.
--
-- `with time zone` matches ci_run's own columns in V1; the entity maps them to java.time.Instant.
alter table ci_step add column started_at timestamp(6) with time zone;
alter table ci_step add column finished_at timestamp(6) with time zone;

-- The status check constraint is deliberately NOT touched. Step rows are now written once, already
-- terminal, so PENDING and RUNNING are never written again — but rows carrying them exist in
-- databases that predate that, the startup sweep still has to be able to read and move them, and
-- narrowing the constraint would only forbid values nothing produces.
