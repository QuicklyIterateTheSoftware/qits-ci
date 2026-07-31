-- The publisher's outbox: events that did not reach qits-events on the first try, and what the
-- sweeper needs to keep trying. Its own lineage on its own datasource, so the module can leave this
-- repository without taking a data migration with it.
--
-- The table is EMPTY in a healthy process. A publish that succeeds inline writes nothing here, and
-- a row that is delivered on retry is deleted — so the row count is a health signal rather than a
-- log, and the log of what actually happened is qits-events.

create table outbox_event (
    -- The EVENT's uuid, not a key of ours. qits-events keys on it, which is what makes a retry a
    -- replay (200) instead of a duplicate.
    id varchar(36) not null primary key,
    -- The envelope, kept whole so a retry re-sends the same bytes rather than re-deriving them:
    -- the server compares payload verbatim, so a row that stored the event's fields instead of its
    -- canonical form could serialize differently later and turn its own retry into a 400.
    name varchar(255) not null,
    occurred_at timestamp(6) with time zone not null,
    payload clob,
    description clob,
    -- Two states only. Delivered rows do not exist; see the header.
    status varchar(16) not null check (status in ('PENDING', 'FAILED')),
    -- Attempts made, INCLUDING the inline one — a row is born at 1.
    attempts int not null,
    -- Null once FAILED.
    next_attempt_at timestamp(6) with time zone,
    last_error varchar(1024),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

-- The sweeper's only query: what is PENDING and due.
create index idx_outbox_event_due on outbox_event (status, next_attempt_at);
