--liquibase formatted sql

--changeset capacity:004-audit-columns
-- Who created and last changed a resource, who made and who closed a hold, and when. These tables
-- are written by guarded native SQL, which does not fire JPA auditing, so the statements write the
-- columns themselves with the subject of the caller's token (see ADR 0003). 'system' is a job or a
-- row from before auditing; 'service' is the recovery-service sweeper. updated_at and created_at of
-- a hold already exist.
ALTER TABLE capacity.capacity_resource ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE capacity.capacity_resource ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE capacity.capacity_resource ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';
UPDATE capacity.capacity_resource SET created_at = updated_at;

ALTER TABLE capacity.capacity_reservation ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE capacity.capacity_reservation ADD COLUMN released_by varchar(100);
--rollback ALTER TABLE capacity.capacity_reservation DROP COLUMN released_by; ALTER TABLE capacity.capacity_reservation DROP COLUMN created_by; ALTER TABLE capacity.capacity_resource DROP COLUMN updated_by; ALTER TABLE capacity.capacity_resource DROP COLUMN created_at; ALTER TABLE capacity.capacity_resource DROP COLUMN created_by;
