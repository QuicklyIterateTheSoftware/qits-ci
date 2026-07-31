-- The envelope grew a fifth field, so the row that holds an envelope whole grows a fifth column.
--
-- THIS IS THE LOAD-BEARING HALF OF THE CAUSATION FEATURE ON THIS SIDE. qits-events compares
-- name + occurred_at + payload + parent_id to tell an idempotent replay (200) from a reused UUID
-- (400), so a sweeper that rebuilt the envelope without the parent would send a DIFFERENT request
-- than the inline attempt it is retrying: 400 against a first attempt that had in fact landed, or —
-- if the first attempt never landed — a silent re-publication of a caused event as a chain root.
-- The parent is fixed when the envelope is built and stored with it, exactly as the payload is, for
-- the reason V1's own comment gives about re-derivation.
--
-- varchar(36) matches this table's id, which is the same kind of thing: a canonical UUID as the
-- publisher wrote it. Nullable, because most events are roots and null is what a root's envelope
-- carries. NO INDEX: the sweeper's only query is (status, next_attempt_at) and nothing here ever
-- selects by parent — the children-of-X read model belongs to qits-events, which does index it.

alter table outbox_event add column parent_id varchar(36);
