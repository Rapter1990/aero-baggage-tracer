--liquibase formatted sql

--changeset recovery:003-capacity-hold
-- The capacity hold a case took when it moved to RESERVED. reservation_id is chosen by this
-- service and sent to the capacity-service, so a hold whose answer was lost can still be released by
-- id. It is cleared when the hold is released; a final case that still has one is retried by the
-- sweeper. reserved_at starts the hold timeout. Both columns are nullable, so existing rows stay valid.
ALTER TABLE recovery.recovery_case ADD COLUMN reservation_id uuid;
ALTER TABLE recovery.recovery_case ADD COLUMN reserved_at timestamptz;

-- The sweeper looks only at cases that hold something.
CREATE INDEX ix_recovery_case_hold ON recovery.recovery_case (reserved_at) WHERE reservation_id IS NOT NULL;
--rollback DROP INDEX recovery.ix_recovery_case_hold; ALTER TABLE recovery.recovery_case DROP COLUMN reserved_at; ALTER TABLE recovery.recovery_case DROP COLUMN reservation_id;
