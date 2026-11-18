--liquibase formatted sql

--changeset recovery:005-anomaly-and-capacity-snapshot
-- A scanner anomaly on the bag of an active case (BR-03) is recorded on the case: the scan data
-- is unreliable, so the case is planned for the service desk and an agent checks the bag. Nullable,
-- so existing rows stay valid.
ALTER TABLE recovery.recovery_case ADD COLUMN scanner_anomaly_at timestamptz;

-- The capacity this service last heard of (recovery.capacity.changed.v1), one row per resource.
-- Only a read model for choosing a recovery action: the capacity-service stays authoritative and
-- every reservation is still decided there. occurred_at orders the events of a resource, so a late
-- older event never overwrites a newer one.
CREATE TABLE recovery.capacity_snapshot (
    resource_id     varchar(64) PRIMARY KEY,
    total_slots     int         NOT NULL,
    remaining_slots int         NOT NULL,
    occurred_at     timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_capacity_snapshot_slots CHECK (total_slots >= 0 AND remaining_slots BETWEEN 0 AND total_slots)
);
--rollback DROP TABLE recovery.capacity_snapshot; ALTER TABLE recovery.recovery_case DROP COLUMN scanner_anomaly_at;
