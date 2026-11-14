--liquibase formatted sql

--changeset journey:002-scan-audit splitStatements:false
-- Every accepted scan, forever. Times are UTC; the zone the device reported and the device's own
-- timestamp are kept next to them. The table is append-only: triggers refuse UPDATE, DELETE and
-- TRUNCATE, so history can be corrected only by a new scan, never rewritten.
CREATE TABLE journey.scan_audit (
    id               uuid        PRIMARY KEY,
    event_id         varchar(128) NOT NULL,
    bag_tag          varchar(10) NOT NULL REFERENCES journey.baggage (bag_tag),
    protocol         varchar(12) NOT NULL,
    airport          varchar(3) NOT NULL,
    checkpoint       varchar(20) NOT NULL,
    device_sequence  bigint      NOT NULL,
    occurred_at      timestamptz NOT NULL,
    device_timestamp timestamptz NOT NULL,
    source_timezone  varchar(40) NOT NULL,
    ingested_at      timestamptz NOT NULL,
    late_event       boolean     NOT NULL DEFAULT false,
    applied          boolean     NOT NULL DEFAULT true,
    CONSTRAINT uq_scan_audit_event_id UNIQUE (event_id)
);
CREATE INDEX ix_scan_audit_bag_time ON journey.scan_audit (bag_tag, occurred_at, ingested_at);

CREATE FUNCTION journey.reject_scan_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'scan_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_scan_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON journey.scan_audit
    FOR EACH ROW EXECUTE FUNCTION journey.reject_scan_audit_change();

CREATE TRIGGER trg_scan_audit_no_truncate
    BEFORE TRUNCATE ON journey.scan_audit
    FOR EACH STATEMENT EXECUTE FUNCTION journey.reject_scan_audit_change();
--rollback DROP FUNCTION journey.reject_scan_audit_change() CASCADE; DROP TABLE journey.scan_audit;
