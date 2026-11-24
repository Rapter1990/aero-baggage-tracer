# ADR 0006: Exactly-once business effects, retries and dead letters

Status: proposed (Kafka infrastructure, issue #15).

## Context

Kafka gives at-least-once delivery between a producer, a broker and a consumer. Kafka's exactly-once mode (`EXACTLY_ONCE_V2`, used by the risk-streams-service) covers a read-process-write cycle that stays inside Kafka. It does not cover a database write, an HTTP call or a second consumer. A business effect (a case opened, a notification created, a document indexed) therefore happens **once only if the effect itself is idempotent**. The system has three places where an event can be seen twice:

1. A producer publishes, the acknowledgement is lost, and the outbox relay publishes the same row again (`KafkaOutageTest`, `JourneyKafkaOutageTest`: an attempt the outage cut off may land twice with the same event id).
2. A consumer processes a record and fails before it commits the offset, so the record is redelivered.
3. A device or an operator sends the same input twice (AT-03) or replays a dataset on purpose (`kafka/replay`).

## Decision

### Producing: transactional outbox, at least once

1. A service that publishes an event writes it to its own `outbox_event` table in the **same database transaction** as the state change (journey-service, recovery-service, capacity-service). The change and the event commit together or not at all.
2. A relay reads pending rows in commit order, sends them keyed as the topic contract says, and marks a row published only after the broker acknowledges it. Kafka down means rows stay pending (AT-14); nothing is lost and nothing is published out of order for one key.
3. The relay is **at least once**. A row may be sent twice with the same `eventId`. This is accepted; it is the consumer's job to make that harmless. `eventId` is created once, when the row is written, and never regenerated on a retry.

### Consuming: the effect is idempotent, the offset is committed last

4. A consumer commits an offset only **after** its effect is durable (or after the event is dead-lettered). A crash in between means a redelivery, never a gap.
5. Each consumer makes the effect idempotent in the way that suits the effect. Kafka's exactly-once mode is **not** relied on for any of them:

| Consumer | Effect | Idempotency |
|----------|--------|-------------|
| recovery-service | opens or updates a case from risk, anomaly and capacity events | `processed_event` table: the event id is inserted in the same transaction as the effect, a conflict means a duplicate. Business rules stay idempotent as well (BR-01: one active case per bag and leg). Ids are kept for `RECOVERY_DEDUPE_RETENTION` (14 days), longer than the 7-day topics |
| notification-service | creates a notification | unique `(recipient, event_id, template)` with `INSERT ... ON CONFLICT DO NOTHING` (BR-08, AT-13); see ADR 0005 |
| search-service | indexes a document | Elasticsearch external versioning: an equal or older version is a `409`, treated as a duplicate (ADR 0004) |
| risk-streams-service | risk and anomaly state | `EXACTLY_ONCE_V2` inside Kafka, plus a durable `scanEventId` deduplication in the state store for `dedupRetention` (AT-03). Exactly-once covers only the topic-to-topic cycle |
| journey-service | accepts a scan | unique source event id and `Idempotency-Key` on the HTTP call: a repeat has no second effect (AT-03) |

6. Order is only guaranteed inside one partition, so each topic is keyed by the aggregate it must keep in order (the contract in `Topics`). A consumer that needs "newest wins" compares a version or an event time and does not rely on arrival order (AT-04).

### Retry and dead letters

7. **A failure is one of two kinds.**
   - *Transient* (a database or Elasticsearch is unavailable, a timeout): retried in place with an exponential backoff, with the consumer's own `retry-attempts` and backoff settings, then dead-lettered. The defaults differ on purpose: the recovery-service gives up after 3 attempts at 200 ms doubling, the notification-service after 6 attempts from 2 s up to 30 s (long enough to outlast a database failover), and the search-service keeps retrying with a backoff of 200 ms up to 30 s, because it holds an index that can be rebuilt.
   - *Poison* (the record is not an event envelope, a field is malformed, the `schemaVersion` is lower than the consumer needs): never retried, dead-lettered at once with `attempts` 1.
8. **A dead letter is an event envelope on `baggage.dead-letter.v1`** with a `DeadLetterPayload`: `originalTopic`, `originalEventId`, `reason`, `attempts` and the original message as `rawPayload` text, so a record that was not valid JSON can still be kept. Key: the original event id (or `topic-partition-offset` when the record has none). The notification-service restores the original `correlationId` on the dead letter; the recovery-service currently writes `null` there and always writes `attempts` 1, even after its retries (a known gap, not fixed by this ADR).
9. **The offset moves on only after the broker has acknowledged the dead letter.** If the dead-letter publish fails, the consumer does not skip the record. A poison record therefore never blocks a partition and is never lost.
10. **Unknown additions are not failures.** A field a newer producer adds is ignored and an unknown enum constant reads as `null` (`EventJson`), so a consumer needs a default branch. Only a breaking change needs a new `.v2` topic; `schemaVersion` is an additive revision inside a topic (`EventEnvelope.requireSchemaVersionAtLeast`).
11. **Dead letters are for people.** `baggage.dead-letter.v1` keeps 30 days (the longest retention of any topic, so a failure is still there after a long weekend). `kafka/dlq/inspect-dlq.ps1` lists them, read only; `rawPayload` is hidden unless `-Raw` is given because it can hold personal data, and it is never written to application logs. A fix is followed by a replay on purpose (`kafka/replay/replay.ps1` shows how); nothing replays a dead letter by itself.

### Topics

12. Topics are created by the `kafka-init` job in Compose with these settings (the Kubernetes provisioning arrives with #18):

| Topic | Key | Partitions | Cleanup | Retention |
|-------|-----|-----------:|---------|-----------|
| `baggage.scan.received.v1` | `bagTag` | 6 | delete | 7 d |
| `baggage.scan.anomaly.v1` | `bagTag` | 6 | delete | 7 d |
| `baggage.risk.changed.v1` | `bagTag:legId` | 6 | **compact,delete** | 90 d |
| `recovery.case.changed.v1` | `caseId` | 6 | delete | 7 d |
| `recovery.capacity.changed.v1` | `resourceId` | 6 | delete | 7 d |
| `baggage.bag.registered.v1` | `bagTag` | 6 | delete | 7 d |
| `baggage.dead-letter.v1` | original event id | 3 | delete | 30 d |

`baggage.risk.changed.v1` is compacted because the search rebuild replays it from offset 0 (ADR 0004): compaction keeps the latest risk per leg, and the 90-day delete retention bounds the history. `kafka-init` also applies this policy to a topic that already exists. The 7-day topics are not a source for any rebuild; the owning service's export endpoints are.

## Consequences

- No consumer can assume "once". Every new consumer must state its idempotency in the table of decision 5 before it is merged.
- `processed_event` and the unique constraints grow with traffic; their retention must stay longer than the topic's retention, or an old redelivery would be applied again.
- A poison record costs one dead letter and an operator's look, never a stuck partition.
- Compacting `baggage.risk.changed.v1` loses intermediate risk changes older than the log cleaner's pass for the same `bagTag:legId`. The search-service only needs the latest, and its hourly report accepts the limit stated in ADR 0004.

## Alternatives considered

- **Rely on Kafka transactions end to end.** Rejected: they do not extend to PostgreSQL or Elasticsearch, and an HTTP caller can still retry.
- **One shared dedupe table for all services.** Rejected: it breaks service-owned schemas and makes every service depend on one database.
- **Dead-letter after a fixed number of attempts everywhere.** Rejected: the right patience differs (a rebuildable index, a notification, a case), so each consumer keeps its own settings, with the poison/transient split common to all.
