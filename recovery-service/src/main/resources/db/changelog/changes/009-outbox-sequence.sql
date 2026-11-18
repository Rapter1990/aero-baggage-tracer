--liquibase formatted sql

--changeset recovery:009-outbox-sequence
-- The relay must publish the events of a case in the order they were written. created_at comes from
-- the clock of whichever replica wrote the row, so replicas with skewed clocks could swap two events
-- of one case. A sequence assigned by the database cannot: the writes of one case are serialised by
-- its version, so a later event always gets a higher number.
ALTER TABLE recovery.outbox_event ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE UNIQUE INDEX ux_outbox_event_seq ON recovery.outbox_event (seq);
CREATE INDEX ix_outbox_event_pending_seq ON recovery.outbox_event (seq) WHERE published_at IS NULL AND parked_at IS NULL;
DROP INDEX recovery.ix_outbox_event_pending;
--rollback CREATE INDEX ix_outbox_event_pending ON recovery.outbox_event (created_at) WHERE published_at IS NULL AND parked_at IS NULL; DROP INDEX recovery.ix_outbox_event_pending_seq; DROP INDEX recovery.ux_outbox_event_seq; ALTER TABLE recovery.outbox_event DROP COLUMN seq;
