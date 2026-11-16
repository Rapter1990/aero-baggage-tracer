# risk-streams-service topology

Source `baggage.scan.received.v1` -> `risk-processor` -> sinks `baggage.risk.changed.v1`,
`baggage.scan.anomaly.v1`, `baggage.dead-letter.v1`. Rationale:
[ADR-001](../adr/ADR-001-risk-streams-time-semantics.md).

| Store | Type | Key | Purpose |
|---|---|---|---|
| `scan-dedup-store` | window, retention `dedup-retention` | scanEventId | durable deduplication |
| `leg-state-store` | key-value | `bagTag:legId` (`bagTag|bag` without a leg) | latest checkpoint, departure, airport, loaded, level |
| `departure-index-store` | key-value | `epochMillis(expiry)|legKey` | the punctuator range-scans only due legs |
| `anomaly-window-store` | window, retention window + grace | bagTag | rejection count and emitted flag per 5 min window |

All stores log to `<application.id>-<store>-changelog`. Processing is `exactly_once_v2`.

Configuration (`aerotrace.risk.*`, see `risk-streams-service/src/main/resources/application.yml`
and `config-repo/risk-streams-service.yml`): `threshold-minutes`, `airport-thresholds.<IATA>`
(minutes), `grace`, `anomaly-window`, `rejected-threshold`, `punctuation-interval`,
`dedup-retention`.

Metrics: `stream_lag_seconds`, `baggage_risk_high`, `risk_streams_late_records`,
`risk_streams_scans_without_leg`, and the Kafka Streams client metrics (consumer lag, rebalance,
state stores). Health: `/actuator/health` (DOWN unless the streams state is RUNNING or
REBALANCING). Metrics leave through OTLP, like journey-service. Port 1114, no REST API.

Tests: `TopologyTestDriver` under `src/test/java/com/aerotrace/riskstreams`. The builders in
`support/` (`ScanBuilder`, `Fixtures`, `TopologyHarness`) are the replay fixtures: they pipe late,
duplicate, out-of-order and poison records deterministically (scan-1, scan-2, ...).
