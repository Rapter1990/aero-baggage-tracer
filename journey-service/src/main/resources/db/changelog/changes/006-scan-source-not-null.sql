--liquibase formatted sql

--changeset journey:006-scan-source-not-null context:contract
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM journey.scan_audit WHERE scan_source IS NULL
-- V3 of the scan_source change (contract step). It always follows V2 in the changelog and runs
-- only when validation passes. It has the context "contract": the service default is
-- "expand,contract" (a fresh database gets both steps), and a rollout against live replicas first
-- sets JOURNEY_LIQUIBASE_CONTEXTS=expand and the next release adds "contract" (ADR 0007).
-- The precondition counts the rows still without a source and halts the migration if there is
-- one, so a half-filled column is never made mandatory. V2 sets a default, so an application
-- that is not updated yet cannot add a row that fails this check.
ALTER TABLE journey.scan_audit ALTER COLUMN scan_source SET NOT NULL;
--rollback ALTER TABLE journey.scan_audit ALTER COLUMN scan_source DROP NOT NULL;
