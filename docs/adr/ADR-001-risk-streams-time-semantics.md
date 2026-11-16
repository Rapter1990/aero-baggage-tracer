# ADR-001: Time semantics, deduplication and delivery guarantees of risk-streams-service

Status: accepted (stage 3 of issue #10). Items marked [UNVERIFIED] rest on the architect's
reconstruction of BR-02, BR-03 and AT-06..AT-08, not on the case study text; the tests that encode
them carry the `unverified-spec` tag.

## Context

risk-streams-service reads `baggage.scan.received.v1` (key bagTag) and publishes
`baggage.risk.changed.v1` (key `bagTag:legId`), `baggage.scan.anomaly.v1` (key bagTag) and
`baggage.dead-letter.v1`. Scans arrive late, out of order and more than once.

## Decisions

### Two clocks, on purpose

- Anomalies (BR-03) use event time: `payload.occurredAt`. A scanner burst is a property of when
  the scans happened, not of when they reached Kafka, so a replay yields the same anomalies.
- Risk (BR-02) uses the wall clock: "less than 20 minutes to departure" is true at a moment in real
  time, also when no scan arrives. A WALL_CLOCK_TIME punctuator (default every 30 s) re-evaluates
  active legs. Replaying old scans therefore does not recreate old HIGH events; this is accepted.

### Rejected transition [UNVERIFIED BR-03]

Checkpoint order is CHECK_IN, SORTING, TRANSFER, LOADED, ARRIVED, CLAIM. A scan is rejected when
its checkpoint is strictly lower than the latest legitimate one of the leg AND its event time is
strictly newer. An older-or-equal time is history (it arrived late), not a rejection; the same
checkpoint again is allowed. Rejected scans never replace the latest checkpoint; raw history is
not discarded (the topic keeps it).

### Anomaly window [UNVERIFIED BR-03]

5-minute tumbling windows aligned to the epoch, `[start, end)`, per bagTag (all legs of the bag
together). The third rejection in a window emits exactly one SCANNER_ANOMALY, at that third scan
(emit-at-threshold, not suppress-until-window-close: no wait for the window end, and a later
fourth rejection emits nothing). The "emitted" flag lives in the window store. Retention is
window + grace.

### Grace and late records

Stream time is the highest `occurredAt` seen by the task. A record is late when
`occurredAt < streamTime - grace` (default 10 min); exactly at the edge is accepted. A late record
is still applied to leg state and risk (a late LOADED confirmation must clear HIGH), but it is
excluded from the anomaly window and counted in `risk_streams_late_records`. Late records are
never dead-lettered. Stream time is held in memory per task, so after a restart it is rebuilt from
new records and the grace check is lenient until then.

### Risk rule [UNVERIFIED BR-02]

HIGH when `departureAt - now < threshold` (strict, default 20 min, per-airport override using the
airport of the latest legitimate scan on the leg) and no non-rejected LOADED scan exists on that
leg. LOADED clears to LOW and is sticky. `risk.changed` is emitted only when the level changes. The
leg is evicted after `departureAt` without output (no LOW on departure). Scans without a leg
(legId or departureAt null) carry no risk; they live in a bag-level pseudo-leg that only feeds the
anomaly rule and expires after the dedup retention.

### Deduplication is a store, not exactly-once

`processing.guarantee=exactly_once_v2` makes read-process-write inside Kafka atomic. It does not
stop the producer (journey-service outbox) from publishing the same scan twice; those are two
different records. So `scan-dedup-store` (changelog-backed window store keyed on `scanEventId`,
retention 7 days) drops redeliveries before any state change or output. Limits: a duplicate older
than the retention is processed again; EOS covers only Kafka-to-Kafka, not metrics or other side
effects; downstream consumers still need to be idempotent (outputs carry deterministic eventIds
for that). Output eventIds are name-based UUIDs derived from the input (scanEventId + type, or
`bagTag:legId:level:departureAt` for punctuator output), so a replay produces identical ids.

### Poison input

Malformed JSON, schemaVersion below 2, unknown checkpoint, missing scanEventId/bagTag/occurredAt
go to `baggage.dead-letter.v1` once, keyed by the original eventId or, when there is none,
`topic-partition-offset`. No blocking retry: the failures are deterministic, retrying cannot fix
them. Replay is from the dead-letter topic after the cause is fixed.

### Partitioning

Key = aggregate id (bagTag), so all scans of a bag hit one task and per-bag order holds. All stores
are partitioned the same way. Partition counts (kafka-init): scan.received 6, risk.changed 6,
scan.anomaly 6, dead-letter 3. Raising the scan topic count later reshuffles keys and breaks the
stores; plan it up front.

## Consequences

- Lag, rebalances and RocksDB state are exposed through the Kafka Streams Micrometer binder, plus
  `stream_lag_seconds` and `baggage_risk_high`.
- A cold restore of the stores is bounded by the changelog size; standby replicas are off by
  default (`RISK_STREAMS_STANDBY_REPLICAS`).
- A leg's state is dropped at departure, so rejections for scans arriving after departure are not
  detected.
