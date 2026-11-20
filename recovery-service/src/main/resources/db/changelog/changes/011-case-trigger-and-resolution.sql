--liquibase formatted sql

--changeset recovery:011-case-trigger-and-resolution
-- Case event revision 2 and the internal export (#29) carry what opened a case and when it was
-- resolved. Both are owned here and written once: case_trigger when the case is opened,
-- resolved_at when it reaches RESOLVED (never for CANCELLED or CLOSED_AS_UNRECOVERABLE).
--
-- Existing rows are backfilled: a case opened by the system came from a risk event (scanner
-- anomalies never open a case), so it is RISK; one opened by a person is MANUAL. resolved_at is the
-- time of the move to RESOLVED in the history.
ALTER TABLE recovery.recovery_case ADD COLUMN case_trigger varchar(20);
UPDATE recovery.recovery_case
   SET case_trigger = CASE WHEN created_by = 'system' THEN 'RISK' ELSE 'MANUAL' END;
ALTER TABLE recovery.recovery_case ALTER COLUMN case_trigger SET NOT NULL;
ALTER TABLE recovery.recovery_case ADD CONSTRAINT ck_recovery_case_trigger
    CHECK (case_trigger IN ('RISK', 'SCANNER_ANOMALY', 'MANUAL'));

ALTER TABLE recovery.recovery_case ADD COLUMN resolved_at timestamptz;
UPDATE recovery.recovery_case c
   SET resolved_at = (SELECT max(t.occurred_at) FROM recovery.state_transition t
                       WHERE t.case_id = c.id AND t.to_state = 'RESOLVED')
 WHERE c.state = 'RESOLVED';
-- The export pages by the primary key (id), which is indexed already: no new index is needed.
--rollback ALTER TABLE recovery.recovery_case DROP COLUMN resolved_at; ALTER TABLE recovery.recovery_case DROP CONSTRAINT ck_recovery_case_trigger; ALTER TABLE recovery.recovery_case DROP COLUMN case_trigger;
