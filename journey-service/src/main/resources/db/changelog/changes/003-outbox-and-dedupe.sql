--liquibase formatted sql

--changeset journey:003-outbox-and-dedupe
-- Transactional outbox: the event is stored in the same transaction as the scan, and a scheduled
-- relay publishes it (at least once). One relay at a time (an advisory lock) keeps the events of
-- a bag in order across replicas; rows are also claimed with FOR UPDATE SKIP LOCKED.
CREATE TABLE journey.outbox_event (
    id           uuid        PRIMARY KEY,
    topic        varchar(100) NOT NULL,
    message_key  varchar(64)  NOT NULL,
    payload      text         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts     int          NOT NULL DEFAULT 0,
    -- Set when the broker refuses the event for good (too large, bad topic): it is skipped, not retried.
    parked_at    timestamptz
);
CREATE INDEX ix_outbox_event_pending ON journey.outbox_event (created_at) WHERE published_at IS NULL AND parked_at IS NULL;

-- Durable dedupe: an event id is inserted here in the same transaction as its effect. A second
-- insert of the same id fails on the primary key, whichever replica handles the retry.
CREATE TABLE journey.processed_event (
    event_id     varchar(128) PRIMARY KEY,
    processed_at timestamptz  NOT NULL DEFAULT now()
);
--rollback DROP TABLE journey.processed_event; DROP TABLE journey.outbox_event;
