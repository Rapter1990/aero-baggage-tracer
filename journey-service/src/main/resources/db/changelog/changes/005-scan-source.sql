--liquibase formatted sql

--changeset journey:005-scan-source splitStatements:false
-- V2 of the scan_source change (expand step). scan_source says how a scan reached the service. The
-- only way in today is the HTTP API, so every scan that exists now was received as 'API'.
--
-- The column is added nullable and without a default, so an application that has not been updated
-- yet can keep inserting. The backfill then fills the rows that exist. The default is set only after
-- the backfill: a default given at ADD COLUMN would make PostgreSQL report 'API' for the old rows
-- without writing them, and there would be nothing to validate before V3.
--
-- scan_audit is append-only: a trigger refuses UPDATE. The backfill is the one intended change to
-- history, so that trigger (and only that one; the TRUNCATE trigger stays on) is switched off for
-- this statement and on again right after. It is all one transaction, and the table is locked
-- until it ends, so no other session sees the table without its protection.
ALTER TABLE journey.scan_audit ADD COLUMN scan_source varchar(20);

ALTER TABLE journey.scan_audit DISABLE TRIGGER trg_scan_audit_no_update_delete;
UPDATE journey.scan_audit SET scan_source = 'API' WHERE scan_source IS NULL;
ALTER TABLE journey.scan_audit ENABLE TRIGGER trg_scan_audit_no_update_delete;

ALTER TABLE journey.scan_audit ALTER COLUMN scan_source SET DEFAULT 'API';
--rollback ALTER TABLE journey.scan_audit DROP COLUMN scan_source;
