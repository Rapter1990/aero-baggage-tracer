--liquibase formatted sql

--changeset identity:005-audit-columns
-- Who created and last changed a user, and when (Spring Data auditing, see ADR 0003). created_at
-- already exists. Rows that exist now were made by the seed data or by a version without auditing,
-- so their user is 'system' and their last change is their creation. The defaults stay: SQL that
-- inserts a row without going through the application (seed data) still gets valid values.
ALTER TABLE identity.app_user ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE identity.app_user ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE identity.app_user ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE identity.app_user SET updated_at = created_at;
--rollback ALTER TABLE identity.app_user DROP COLUMN updated_at; ALTER TABLE identity.app_user DROP COLUMN updated_by; ALTER TABLE identity.app_user DROP COLUMN created_by;
