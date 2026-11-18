--liquibase formatted sql

--changeset recovery:004-planned-action
-- The action the recovery strategy chose when a case was opened from a risk event (expedited
-- transfer, reroute, hold at the service desk). Nullable: cases opened through the API by an agent
-- have none, and existing rows stay valid.
ALTER TABLE recovery.recovery_case ADD COLUMN planned_action varchar(30);
ALTER TABLE recovery.recovery_case ADD CONSTRAINT ck_recovery_case_planned_action
    CHECK (planned_action IS NULL OR planned_action IN ('EXPEDITED_TRANSFER', 'REROUTE_NEXT_FLIGHT', 'HOLD_AT_SERVICE_DESK'));
--rollback ALTER TABLE recovery.recovery_case DROP CONSTRAINT ck_recovery_case_planned_action; ALTER TABLE recovery.recovery_case DROP COLUMN planned_action;
