--liquibase formatted sql

--changeset capacity:002-outbox
-- Transactional outbox: recovery.capacity.changed.v1 is stored in the same transaction as the
-- change of the slots, and a scheduled relay publishes it (at least once). One relay at a time (an
-- advisory lock) keeps the events of a resource in order across replicas; rows are also claimed
-- with FOR UPDATE SKIP LOCKED. seq is assigned by the database, so a replica clock cannot reorder.
CREATE TABLE capacity.outbox_event (
    id           uuid         PRIMARY KEY,
    topic        varchar(100) NOT NULL,
    message_key  varchar(64)  NOT NULL,
    payload      text         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts     int          NOT NULL DEFAULT 0,
    -- Set when the broker refuses the event for good (too large, bad topic): it is skipped, not retried.
    parked_at    timestamptz,
    seq          bigint       GENERATED ALWAYS AS IDENTITY
);
CREATE UNIQUE INDEX ux_outbox_event_seq ON capacity.outbox_event (seq);
CREATE INDEX ix_outbox_event_pending_seq ON capacity.outbox_event (seq) WHERE published_at IS NULL AND parked_at IS NULL;
--rollback DROP TABLE capacity.outbox_event;
