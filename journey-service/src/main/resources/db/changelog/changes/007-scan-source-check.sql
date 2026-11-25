--liquibase formatted sql

--changeset journey:007-scan-source-check
-- The known sources. A NULL passes (the column is nullable until V3); every other value must be
-- listed. NOT VALID adds the constraint for new rows at once without scanning scan_audit, which
-- only grows: the existing rows are checked by the next changeset, which needs a weaker lock. A
-- new source is a new changeset that replaces this constraint in the same two steps (ADR 0007).
ALTER TABLE journey.scan_audit
    ADD CONSTRAINT ck_scan_audit_scan_source CHECK (scan_source IN ('API')) NOT VALID;
--rollback ALTER TABLE journey.scan_audit DROP CONSTRAINT ck_scan_audit_scan_source;
