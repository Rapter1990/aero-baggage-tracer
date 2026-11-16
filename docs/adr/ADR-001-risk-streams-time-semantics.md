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
together). A scan without a leg is never a rejection (see "Scans without a leg"). The third rejection in a window emits exactly one SCANNER_ANOMALY, at that third scan
(emit-at-threshold, not suppress-until-window-close: no wait for the window end, and a later
fourth rejection emits nothing). The "emitted" flag lives in the anomaly store (key `bagTag|windowStart`). The entry is deleted by
the wall-clock punctuator once it has not been touched for window + grace (see "Store expiry").

### Grace and late records

Stream time is the highest `occurredAt` seen by the task, with each advance clamped to the wall
clock plus `max-clock-skew` (default 5 min, the value journey-service uses for the same purpose).
A scan dated far in the future is still processed but cannot make later on-time scans late or
suppress their anomaly count. A record is late when
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
(legId or departureAt null) carry no risk and are described next.

### Scans without a leg

A scan without a leg updates no leg state and never counts as a rejected transition: it is
counted in `risk_streams_scans_without_leg` and is otherwise only deduplicated (the raw history
stays in the topic). Earlier these scans shared one bag-level pseudo-leg, so a legitimate
connection (ARRIVED or CLAIM, then TRANSFER or CHECK_IN for the next flight) looked like a
rejected transition and could raise a false SCANNER_ANOMALY. Without a leg there is nothing to
compare against, so no anomaly can come from such scans. Pseudo-leg entries written by an earlier
version are no longer read and expire after the dedup retention.

### Deduplication is a store, not exactly-once

`processing.guarantee=exactly_once_v2` makes read-process-write inside Kafka atomic. It does not
stop the producer (journey-service outbox) from publishing the same scan twice; those are two
different records. So `scan-dedup-store` (changelog-backed key-value store keyed on `scanEventId`,
retention 7 days from first processing) drops redeliveries before any state change or output. Limits: a duplicate older
than the retention is processed again; EOS covers only Kafka-to-Kafka, not metrics or other side
effects; downstream consumers still need to be idempotent (outputs carry deterministic eventIds
for that). Output eventIds are name-based UUIDs derived from the input (scanEventId + type, or
`bagTag:legId:level:departureAt` for punctuator output), so a replay produces identical ids.

### Store expiry

Dedup and anomaly counts are key-value stores, not window stores. A Kafka Streams window store
expires segments against the highest timestamp it has seen and silently drops writes older than
the retention; with a replayed or delayed backlog (occurredAt days older than "now", or after a
restart when stream time starts over but the restored store does not) that loses dedup marks and
anomaly counts. So expiry never looks at the record timestamp or occurredAt. The dedup store keeps
`id|<scanEventId>` plus an index `ts|<firstProcessedMillis>|<scanEventId>`; the anomaly store keeps
a last-touched wall-clock time in the value. The WALL_CLOCK punctuator range-deletes dedup entries
older than the retention and anomaly entries untouched for window + grace (at most 10000 per store
per tick). Both stores keep their changelogs (`<application.id>-<store>-changelog`). Retention is
counted from first processing, so a replayed old scan is remembered for the full retention. Late
events are still decided by event time and the grace period, not by the store.

### Scans with a legId but no departureAt

If the leg already has state, the scan is applied to it with the stored departure (a LOADED scan
still clears HIGH, a lower checkpoint still counts as a rejection). It is skipped, and counted in
`risk_streams_scans_without_leg`, only when legId is null, or when the leg has no state and the
departure is unknown.

### Poison input

Malformed JSON, schemaVersion below 2, unknown checkpoint, missing scanEventId/bagTag/occurredAt
go to `baggage.dead-letter.v1` once, keyed by the original eventId or, when there is none,
`topic-partition-offset`. No blocking retry: the failures are deterministic, retrying cannot fix
them. Replay is from the dead-letter topic after the cause is fixed.

An unexpected exception while processing a record that did parse (reason `PROCESSING_ERROR`) is
handled the same way: the record is dead-lettered once (attempts 1), `risk_streams_processing_errors`
is incremented and the stream goes on. Otherwise the uncaught-exception handler (REPLACE_THREAD)
would re-read the same offset forever. The record is handled in two steps: planning reads state and
builds the outputs (serialized) without writing anything, then the effects are applied. A failure
while planning leaves no dedup mark, state write or output behind. Store and client failures
(`KafkaException`, which covers state store errors) are not poison: they are rethrown, the task
fails, the exactly-once transaction aborts and the record is retried, so a good scan is never
dead-lettered because of an infrastructure error. Under EOS the Kafka stores and outputs roll back
then, but in-memory state (stream time, the HIGH-legs gauge, metric counters) does not; those
effects are applied last to keep that window small, and a retry only makes them slightly
conservative. Logs carry the record location and exception class, never the payload.

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

### Changing the type of a store

Dedup and anomaly counts changed from window stores to key-value stores under the same store and
changelog names. risk-streams-service is not deployed anywhere yet, so no migration is needed. Any
environment that already ran an earlier build must be reset: run `kafka-streams-application-reset`
for the application.id, delete the state volume and delete the old changelog topics
(`<application.id>-scan-dedup-store-changelog`, `<application.id>-anomaly-window-store-changelog`).
Known limits: anomaly entries are touched by wall clock but keyed by event-time window, so after
more than window + grace (15 min) of quiet a late replay of the same window can raise a second
SCANNER_ANOMALY for it; and expiry deletes at most 10000 entries per store per tick, so a very
large backlog of expired entries is cleared over several ticks.
