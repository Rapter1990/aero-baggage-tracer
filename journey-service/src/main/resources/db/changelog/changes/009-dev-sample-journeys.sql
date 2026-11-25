--liquibase formatted sql

--changeset journey:009-dev-sample-journeys context:dev
--preconditions onFail:MARK_RAN
--precondition-sql-check expectedResult:0 SELECT count(*) FROM journey.baggage
-- Twenty sample bags with a two-leg itinerary and three scans each, for local development and
-- demos. They exist only when Liquibase runs with the "dev" context
-- (JOURNEY_LIQUIBASE_CONTEXTS=expand,contract,dev); without it this changeset is skipped, so a
-- real deployment never gets them (an empty setting is turned into the default, see
-- LiquibaseContextsGuard). The precondition also skips it, and marks it run, on a database that
-- already holds bags, so it can never collide with real data. The bag tags are 9000000001 to
-- 9000000020 and the passengers P-00000000000000000001 and up. The rows are written straight to
-- the tables, so no event is published: the search-service does not know these bags until it is
-- rebuilt. Each bag's latest_* columns are those of its newest scan, as the application keeps them.
INSERT INTO journey.baggage (id, bag_tag, passenger_ref)
SELECT gen_random_uuid(), (9000000000 + n)::text, 'P-' || lpad(n::text, 20, '0')
FROM generate_series(1, 20) n;

INSERT INTO journey.itinerary (id, baggage_id)
SELECT gen_random_uuid(), id FROM journey.baggage
WHERE bag_tag BETWEEN '9000000001' AND '9000000020';

INSERT INTO journey.itinerary_leg (id, itinerary_id, leg_no, flight_number, origin, destination,
                                   departure_at, arrival_at)
SELECT gen_random_uuid(), i.id, leg, 'TK' || (1000 + leg),
       CASE leg WHEN 1 THEN 'IST' ELSE 'FRA' END, CASE leg WHEN 1 THEN 'FRA' ELSE 'LHR' END,
       now() + make_interval(hours => 2 + leg * 4), now() + make_interval(hours => 5 + leg * 4)
FROM journey.itinerary i
JOIN journey.baggage b ON b.id = i.baggage_id
     AND b.bag_tag BETWEEN '9000000001' AND '9000000020',
     generate_series(1, 2) leg;

INSERT INTO journey.scan_audit (id, event_id, bag_tag, protocol, airport, checkpoint,
                                device_sequence, occurred_at, device_timestamp, source_timezone,
                                ingested_at, late_event, applied, scan_source)
SELECT gen_random_uuid(), 'sample-' || b.bag_tag || '-' || s, b.bag_tag, 'BARCODE', 'IST',
       (ARRAY['CHECK_IN', 'SORTING', 'LOADED'])[s], s,
       now() - make_interval(mins => 30 - s * 5), now() - make_interval(mins => 30 - s * 5),
       'Europe/Istanbul', now(), false, true, 'API'
FROM journey.baggage b, generate_series(1, 3) s
WHERE b.bag_tag BETWEEN '9000000001' AND '9000000020';

UPDATE journey.baggage b
SET latest_scan_id = a.id, latest_airport = a.airport, latest_checkpoint = a.checkpoint,
    latest_occurred_at = a.occurred_at, latest_device_sequence = a.device_sequence
FROM journey.scan_audit a
WHERE a.bag_tag = b.bag_tag AND a.event_id = 'sample-' || b.bag_tag || '-3'
  AND b.bag_tag BETWEEN '9000000001' AND '9000000020';
--rollback DELETE FROM journey.itinerary_leg WHERE itinerary_id IN (SELECT i.id FROM journey.itinerary i JOIN journey.baggage b ON b.id = i.baggage_id WHERE b.bag_tag BETWEEN '9000000001' AND '9000000020');
--rollback DELETE FROM journey.itinerary WHERE baggage_id IN (SELECT id FROM journey.baggage WHERE bag_tag BETWEEN '9000000001' AND '9000000020');
--rollback ALTER TABLE journey.scan_audit DISABLE TRIGGER trg_scan_audit_no_update_delete;
--rollback DELETE FROM journey.scan_audit WHERE bag_tag BETWEEN '9000000001' AND '9000000020' AND event_id LIKE 'sample-%';
--rollback ALTER TABLE journey.scan_audit ENABLE TRIGGER trg_scan_audit_no_update_delete;
--rollback DELETE FROM journey.baggage WHERE bag_tag BETWEEN '9000000001' AND '9000000020';
