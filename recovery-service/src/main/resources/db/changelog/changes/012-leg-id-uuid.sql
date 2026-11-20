--liquibase formatted sql

--changeset recovery:012-leg-id-uuid
-- The journey-service issues leg ids as 36 character UUIDs, but leg_id was varchar(32), so risk
-- events for real legs were refused. Widening a varchar keeps every value and rewrites no rows.
ALTER TABLE recovery.recovery_case ALTER COLUMN leg_id TYPE varchar(36);
ALTER TABLE recovery.risk_event_clock ALTER COLUMN leg_id TYPE varchar(36);
--rollback ALTER TABLE recovery.risk_event_clock ALTER COLUMN leg_id TYPE varchar(32);
--rollback ALTER TABLE recovery.recovery_case ALTER COLUMN leg_id TYPE varchar(32);
