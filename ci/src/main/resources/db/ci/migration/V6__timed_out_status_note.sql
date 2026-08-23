-- TIMED_OUT joined the status vocabularies in 2026.823 (qits-ci c7465fb): a step that hits its
-- deadline is recorded TIMED_OUT, and so is its run unless a cancel wins.
--
-- No statement follows, and that is the point of this file. Neither status column is checked in
-- the database (see V1's header), so nothing has to change — but V1's comments were edited to say
-- TIMED_OUT, and Flyway refused the checksum at the next boot (2026-08-23, qits-ci 2026.823.164332
-- rolled back). V1 is back to its applied bytes; the note lives here, where a new file belongs.
select 1;
