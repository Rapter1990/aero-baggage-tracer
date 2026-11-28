--liquibase formatted sql

--changeset journey:008-scan-source-check-validate
-- Checks the rows that existed before 007. VALIDATE CONSTRAINT takes SHARE UPDATE EXCLUSIVE, so
-- inserts and reads continue while it scans. It is a changeset of its own so that it does not run
-- in the transaction that holds the stronger lock of ADD CONSTRAINT.
ALTER TABLE journey.scan_audit VALIDATE CONSTRAINT ck_scan_audit_scan_source;
--rollback SELECT 1;
