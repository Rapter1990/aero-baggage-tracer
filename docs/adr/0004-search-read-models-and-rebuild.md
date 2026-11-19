# ADR 0004: Search read models and rebuild

Status: proposed (search-service, issue #13; follow-ups #28 and #29, and a bullet in #15)

## Context

The search-service answers "find a bag or a case" and the airport report from Elasticsearch. Elasticsearch is a read model: it must be rebuildable from the services that own the data (AT-18, "Rebuild Elasticsearch from source data with no duplicate documents"), without touching them.

A review of the code before the build found four gaps:

1. **No durable source.** Every topic is `cleanup.policy=delete` with 7-day retention (`docker-compose.yml`), and the journey and recovery outboxes are purged. A rebuild from Kafka alone is silently incomplete after a week. Reading another service's database is forbidden (one schema per service).
2. **The filters are not in the events.** `CaseChangedPayload` carries `caseId, bagTag, legId, state, version, reason`; `RiskChangedPayload` carries `bagTag, legId, previous, current, departureAt`; `ScanReceivedPayload` has the scan airport, `legId`, `flightNumber`, `departureAt`. No event has the airline, the leg's origin and destination, or the case's `openedAt` and `resolvedAt`. The airline exists nowhere in the system.
3. **No search-side ownership.** No event carries `passengerRef`, so search cannot apply journey's passenger ownership rule (AT-15).
4. **No Elasticsearch in Compose**, and the gateway routes only `/api/v1/reports/**` to the search-service.

## Decision

1. **Rebuild sources (AT-18).** Each owning service exports its own current state through an internal, read-only, keyset-paged endpoint (`GET /internal/v1/export/...?after=&size=`), authenticated with a service token like capacity's `X-Service-Token`, and with no gateway route: journey-service exports bags with their legs (#28), recovery-service exports cases with `version`, `openedAt` and `resolvedAt` (#29). Risk level has no database owner (risk-streams keeps it in a KTable), so `baggage.risk.changed.v1` becomes `cleanup.policy=compact,delete` with a long retention (its key `bagTag:legId` suits compaction; #15) and a rebuild replays it from offset 0. A rebuild never reads another service's database and never relies on the 7-day topics. **Accepted limit:** hourly risk history older than the retention window cannot be rebuilt.
2. **"Source untouched" means** the reconciler issues only `GET`s and consumes only from Kafka; it writes nothing to the source schemas, their outboxes or Kafka topics; source row counts and checksums are identical before and after.
3. **Events.** No enrichment calls from the search-service. The producers add what consumers need, additively (`EventEnvelope` schema revision, never an edit in place):
   - `CaseChangedPayload` revision 2 adds `openedAt`, `resolvedAt`, `trigger` (`RISK`, `SCANNER_ANOMALY`, `MANUAL`) and `assignedTo`, and `CASE_EVENT_SCHEMA_VERSION` moves from 1 to 2 (#29);
   - journey-service publishes `baggage.bag.registered.v1` (key `bagTag`, added to `Topics.ALL`) with the itinerary snapshot per leg: `legId`, `flightNumber`, `airline`, `origin`, `destination`, `departureAt` (#28). The airline is the validated carrier prefix of the flight number, written down as a rule, not guessed by consumers.
4. **Indexes and ids.**
   - `cases`: one document per case, `_id = caseId`, fed by `recovery.case.changed.v1`. Leg attributes (airport, airline, flight) come from a search-owned leg lookup keyed by `legId`, filled from `baggage.bag.registered.v1`; when the leg arrives after the case, an `_update_by_query` on `legId` completes the document.
   - `journeys`: one document per bag with nested legs, `_id = bagTag`, fed by `baggage.bag.registered.v1`, `baggage.scan.received.v1` and `baggage.risk.changed.v1`.
   - Strict mapping (`dynamic: strict`) from an index template for `cases-*` and `journeys-*`: `keyword` for `caseId`, `bagTag`, `legId`, `airport`, `airline`, `flightNumber`, `riskLevel`, `state`, `trigger`; `date` (UTC) for the times; `long` for `version`; `resolutionSeconds` stored at indexing time. Free-text `reason` is `text` only, never a filter or sort field, and no passenger data is indexed.
   - "Risk category" is the leg's `RiskLevel` (`LOW`, `MEDIUM`, `HIGH`), filter name `riskLevel`; the case `trigger` is a separate keyword.
5. **Idempotency without a `processed_event` table** (it cannot share a transaction with an Elasticsearch write, and the service would need a database it otherwise does not):
   - cases: `version_type=external` with `payload.version` (it rises by one on every case event); an Elasticsearch `409 version_conflict` is a successful duplicate, counted as `outcome=stale`, never dead-lettered;
   - journeys: a scripted upsert; the latest scan is replaced only when `(occurredAt, deviceSequence)` is newer (journey's own rule), and a leg's risk only when the event's `occurredAt` is newer (recovery's risk clock). Two topics with unrelated clocks write this document, so one external version cannot work;
   - the offset is committed only after the bulk request is acknowledged, bulk responses are checked per item, transient failures (429, 503, timeout) retry with backoff, and poison records go to `baggage.dead-letter.v1` like recovery's consumers;
   - a case is never deleted (its lifecycle has no delete), so there are no tombstones.
6. **Rebuild without downtime.** Two aliases per index: `cases-read` points to exactly one index; `cases-live` is the set the indexer writes to. Steps: create `cases-vN+1` from the template; add it to `cases-live` (live events now write to both); bulk-load the export (and replay the compacted risk topic for `journeys`) with external versions; compare document counts and checksums with the source; swap `cases-read` in one atomic `_aliases` call; remove the old index from `cases-live`; delete it only after the longest point-in-time keep-alive has passed. External versions and scripted guards give the same result in any order, so live and snapshot writes may overlap. A rebuild job is started by `POST /api/v1/admin/search/reindex` (ADMIN, `Idempotency-Key`, `202` with a `jobId`, `409 REINDEX_IN_PROGRESS` while one runs).
7. **Reports.** `/api/v1/reports/**` stays in the search-service. Results are eventually consistent: every response carries `indexedThrough` (a watermark) and `lagSeconds`. Buckets are hourly in UTC over a half-open range `[from, to)` with a maximum length (31 days); empty hours are zero-filled; an airport with no data is `200`, not `404`. Counts come from documents with deterministic ids, not from approximate aggregations. Definitions:
   - **risky bags:** distinct bags whose leg had `RiskLevel.HIGH` in the hour of the risk event at that airport (the airport of the leg's origin);
   - **resolved recoveries:** cases whose state is `RESOLVED`, bucketed by `resolvedAt`; `CANCELLED` and `CLOSED_AS_UNRECOVERABLE` are not counted;
   - **mean resolution time:** mean of `resolvedAt - openedAt` in seconds over the cases resolved in the bucket, `null` when there are none; totals are weighted by count;
   - **retries:** cases opened for a bag and leg after an earlier case for the same bag and leg ended in `CANCELLED` or `CLOSED_AS_UNRECOVERABLE`, bucketed by `openedAt`. This is the working definition; change it here before the build if the case study says otherwise (sections 5.4 and 12 are not in the repository, so it could not be checked).
8. **Access.** Search and reports are staff-only: `AGENT`, `DISPATCHER` and `ADMIN` read; `/api/v1/search/index/status` is `DISPATCHER` and `ADMIN`; reindex is `ADMIN`; `PASSENGER` gets `403` everywhere. This keeps `passengerRef` (personal data) out of Elasticsearch. The service enforces the roles itself; the gateway rules are only a coarse edge check. A later decision to open search to passengers needs `passengerRef` on an event (schema revision) and a forced server-side owner filter that ignores client filters, and an AT-15 style test.
9. **Lag.** `search.index.lag` (gauge, seconds) is the consumer-group lag plus the age (`now - publishedAt`) of the last processed batch, zero when caught up (a plain "now minus last `occurredAt`" would grow on an idle topic). Also `search.index.lag.events`, `search.index.events{outcome=indexed|stale|duplicate|failed}`, `search.index.delay`, `search.reindex.runs`, and `GET /api/v1/search/index/status`. The Grafana panel is added with #19.
10. **Elasticsearch runtime.** A pinned image tag in Compose and in the Testcontainers test; Compose runs a single node with `xpack.security.enabled=true`, a password without default (`ELASTIC_PASSWORD`), a heap limit, a named volume, a health check, and port 9200 not published (or bound to loopback in `docker-compose-local.yml`). The plain `co.elastic.clients` Java API client is used instead of Spring Data Elasticsearch repositories, which hide external versioning and scripted upserts; `spring-data-elasticsearch` joins the banned dependencies of `common-module`, and no Elasticsearch type lives there.
11. **Contract.** `GET /api/v1/search/bags` with optional AND-combined filters `bagTag`, `airport`, `airline`, `flight`, `riskLevel`, `caseState`; `size` default 20, maximum 100, out of range is `400 VALIDATION_ERROR`; sort `updatedAt desc` then a stored `docId` keyword (never `_id`); an opaque, signed cursor over a point-in-time (a forged cursor is `400`, an expired one `410 CURSOR_EXPIRED`); `ApiResponse` envelope with `{items, size, nextCursor, hasMore}`; no raw Elasticsearch hits or scores. New error codes `SEARCH_UNAVAILABLE` (503) and `REINDEX_IN_PROGRESS` (409), thrown with explicit codes (a bare 409 would map to `VERSION_CONFLICT`). The gateway gains `/api/v1/search/**` and `/api/v1/admin/search/**`. The consumer restores the envelope `correlationId` into the logging context and never generates one.

## Consequences

- #13 can pass AT-18 for more than the last 7 days and prove "no duplicates" with a Testcontainer; its text is rewritten accordingly.
- The scope of closed #9 and #11 grows (#28, #29), and #15 gains the compaction and the new topic. #13 cannot be finished before them, and "lag visible in Grafana" waits for #19.
- Journey and case events grow by a few fields; old events read the new fields as `null`, so consumers must tolerate that.
- Hourly risk history older than the compacted topic's retention cannot be rebuilt.
- A search outage never affects the services that own the data: the index is derived and can be dropped and rebuilt.
- Recovery's consumers drop the correlation id (they never set it in the logging context); the search-service does not repeat that, and the gap is noted for #11 and #19.

## Alternatives considered

- **Rebuild from Kafka replay only.** Not durable (7-day retention, purged outboxes); rejected for everything except the compacted risk topic.
- **Read the owning services' databases.** Breaks schema ownership.
- **Enrichment calls from the indexer.** Couples search to the availability of journey and recovery and breaks idempotent replay.
- **A `processed_event` table in a search schema.** Cannot be atomic with Elasticsearch; external versions give the same guarantee.
- **A single write alias for the rebuild.** Allows one write index only, so live events written during the build would be lost.
- **Passenger access with an owner filter.** Needs `passengerRef` in Elasticsearch (personal data) and a second ownership implementation; deferred.
