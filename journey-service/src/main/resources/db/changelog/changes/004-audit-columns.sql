--liquibase formatted sql

--changeset journey:004-audit-columns
-- Who created and last changed a bag and its itinerary, and when (Spring Data auditing, see ADR
-- 0003). itinerary already has created_at. For a bag, created_at starts as registered_at. Rows that
-- exist now were made by a version without auditing: their user is 'system' and their last change
-- is their creation. The defaults stay, so SQL that inserts a row directly still gets valid values.
ALTER TABLE journey.baggage ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE journey.baggage ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE journey.baggage ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE journey.baggage ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE journey.baggage SET created_at = registered_at, updated_at = registered_at;

ALTER TABLE journey.itinerary ADD COLUMN created_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE journey.itinerary ADD COLUMN updated_by varchar(100) NOT NULL DEFAULT 'system';
ALTER TABLE journey.itinerary ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE journey.itinerary SET updated_at = created_at;
--rollback ALTER TABLE journey.itinerary DROP COLUMN updated_at; ALTER TABLE journey.itinerary DROP COLUMN updated_by; ALTER TABLE journey.itinerary DROP COLUMN created_by; ALTER TABLE journey.baggage DROP COLUMN updated_at; ALTER TABLE journey.baggage DROP COLUMN updated_by; ALTER TABLE journey.baggage DROP COLUMN created_at; ALTER TABLE journey.baggage DROP COLUMN created_by;
