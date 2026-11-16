# risk-streams-service topology

Source `baggage.scan.received.v1` -> `risk-processor` -> sinks `baggage.risk.changed.v1`,
`baggage.scan.anomaly.v1`, `baggage.dead-letter.v1`. Rationale:
[ADR-001](../adr/ADR-001-risk-streams-time-semantics.md).

| Store | Type | Key | Purpose |
|---|---|---|---|
| `scan-dedup-store` | key-value | `id|<scanEventId>` (value: first-processed wall-clock millis) and `ts|<millis>|<scanEventId>` (expiry index) | durable deduplication; the punctuator range-deletes entries older than `dedup-retention` |
| `leg-state-store` | key-value | `bagTag:legId` (scans without a leg touch no leg state) | latest checkpoint, departure, airport, loaded, level |
| `departure-index-store` | key-value | `epochMillis(expiry)|legKey` | the punctuator range-scans only due legs |
| `anomaly-window-store` | key-value | `bagTag|windowStartMillis` | rejection count, emitted flag and last-touched wall-clock time per 5 min window; the punctuator deletes entries untouched for window + grace |

There are no window stores: Kafka Streams expires window-store segments against the highest
timestamp the store has seen, which silently drops writes of a replayed or delayed backlog.
Expiry is done by the wall-clock punctuator instead (at most 10000 deletes per store per tick).

All stores log to `<application.id>-<store>-changelog`. Processing is `exactly_once_v2`.

Configuration (`aerotrace.risk.*`, see `risk-streams-service/src/main/resources/application.yml`
and `config-repo/risk-streams-service.yml`): `threshold-minutes`, `airport-thresholds.<IATA>`
(minutes), `grace`, `anomaly-window`, `rejected-threshold`, `punctuation-interval`,
`dedup-retention`, `max-clock-skew` (default 5m, as journey-service: stream time never advances
beyond wall clock + skew).

Metrics: `stream_lag_seconds`, `baggage_risk_high`, `risk_streams_late_records`,
`risk_streams_scans_without_leg`, `risk_streams_processing_errors`, and the Kafka Streams client metrics (consumer lag, rebalance,
state stores). Health: `/actuator/health` (DOWN unless the streams state is RUNNING or
REBALANCING). Metrics leave through OTLP, like journey-service. Port 1114, no REST API.

Dead letters: reasons `MALFORMED_JSON`, `UNSUPPORTED_SCHEMA_VERSION`, `INVALID_PAYLOAD` and
`PROCESSING_ERROR` (the record parsed but the processor failed on it: nothing of it is applied and
it is dead-lettered once, attempts 1, so a poison pill cannot loop, and nothing is written to the stores or output topics; store and client failures are not dead-lettered but fail the task and are retried (in-memory state such as the HIGH gauge may stay changed after such a failure); the log carries the location
and the exception class, never the payload).

Tests: `TopologyTestDriver` under `src/test/java/com/aerotrace/riskstreams`. The builders in
`support/` (`ScanBuilder`, `Fixtures`, `TopologyHarness`) are the replay fixtures: they pipe late,
duplicate, out-of-order and poison records deterministically (scan-1, scan-2, ...).
