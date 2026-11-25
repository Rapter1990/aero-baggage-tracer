# Traceability: business rules and acceptance tests to issues

Sources of truth: [BUSINESS_RULES.md](BUSINESS_RULES.md) and [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md) (copied from the case study, sections 6 and 18). The owning issue is taken from `docs/issues/GITHUB_ISSUES.md`.

**Convention.** A test that proves a rule or scenario carries its id in the `@DisplayName` (for example `AT-09 two parallel requests create one case`) and in its Javadoc/comment for BR-xx. The "Test" column is filled in by the PR that adds the test. Backlog issue #21 reviews the whole table.

## Business rules

| Rule | Owning issue | Test |
|------|--------------|------|
| BR-01 | #11 Recovery Service | `RecoveryCaseApiTest` (AT-09 and the BR-01 cases), `RiskEventHandlerTest` (events never open a second case) |
| BR-02 | #10 Risk Streams | `LegRiskTest`, `AcceptanceScenariosTest` (AT-07) |
| BR-03 | #10 Risk Streams | `ScanAnomalyTest`, `AcceptanceScenariosTest` (AT-06); consumed in #11: `ScanAnomalyHandlerTest` |
| BR-04 | #12 Capacity Service | `ReservationApiTest` (AT-11 and the BR-04 cases), `RedisOfflineTest` (AT-19), `OpenApiDocsTest` (the 409 contract) |
| BR-05 | #11 Recovery Service | `RecoveryStateMachineTest`, `RecoveryCaseApiTest` |
| BR-06 | #9 Journey Service (also #10 for the stream grace) | `LateEventGraceTest` (stream side) |
| BR-07 | #11 Recovery Service | `RecoveryStateMachineTest`, `RecoveryCaseApiTest` |
| BR-08 | #14 Notification Service | `NotificationRepositoryTest` (the unique `(recipient, event_id, template)` key), `CaseChangedHandlerTest`, `RecipientResolverTest`, `TemplateSelectorTest`, `DeliveryDispatcherTest` (retries never make a second row), `BackoffPolicyTest`, `PiiLoggingTest` |

## Acceptance tests

| Scenario | Owning issue | Test |
|----------|--------------|------|
| AT-01 | #9 | not tagged with its id yet |
| AT-02 | #9 | not tagged with its id yet |
| AT-03 | #9, #10 | not tagged with its id yet (`ScanDeduplicationTest` covers the stream side) |
| AT-04 | #9, #10 | not tagged with its id yet (`LateEventGraceTest` covers the stream side) |
| AT-05 | #9 | not tagged with its id yet |
| AT-06 | #10 | `AcceptanceScenariosTest` |
| AT-07 | #10 | `AcceptanceScenariosTest` |
| AT-08 | #10 | `AcceptanceScenariosTest` |
| AT-09 | #11 | `RecoveryCaseApiTest` |
| AT-10 | #11 | `RecoveryStateMachineTest`, `RecoveryCaseApiTest` |
| AT-11 | #12 | `ReservationApiTest` (50 parallel requests, 10 slots, real PostgreSQL) |
| AT-12 | #11 | `CapacitySagaTest` (against a stub capacity-service; the real service is #12) |
| AT-13 | #14 | `CaseChangedKafkaTest` (same event published twice, redelivery after a consumer restart), `CaseChangedHandlerTest` (16 threads, one event), `NotificationRepositoryTest` (16 writers, one winner) |
| AT-14 | #11, #15 | `KafkaOutageTest` (a real broker is paused during a move: it commits, the events stay pending, and are published in order after the broker is back, at least once: an attempt the outage cut off may land twice with the same event id, which consumers dedupe); `RecoveryCaseApiTest` (case level). The journey-service's outbox is #15's: `JourneyKafkaOutageTest` (a real broker is paused while three scans are accepted: they commit, their events stay pending, and are published in commit order after the broker is back, at least once). Contracts: `TopicContractTest`, `ReplayDatasetTest` (AT-03, AT-04, AT-05 input data) |
| AT-15 | #9, #8 | not tagged with its id yet |
| AT-16 | #7 | `GatewaySecurityTest` |
| AT-17 | #16 | `ScanSourceMigrationTest` (the real journey-service changelog on PostgreSQL in Testcontainers: V1 applied, legacy rows loaded, V2 and V3 applied; row count and checksum of every old column equal before and after, `scan_source` mandatory, `scan_audit` still append-only, a second run changes nothing, V3 halts while a row has no source). Demo: `docs/migrations/zero-data-loss-demo.ps1`; strategy: ADR 0007 |
| AT-18 | #13 (sources #28, #29) | End to end: `RebuildAgainstRealOwnersTest`. The search-service's real export client and rebuild job (scope `ALL`) read the real `/internal/v1/export/...` endpoints of the packaged recovery-service and journey-service jars, running as their own processes on a real PostgreSQL and publishing through their outboxes to the Kafka the search-service consumes; Elasticsearch is the pinned Testcontainers image. The source is created through the owners' public APIs: 150 cases (more than one export page of 100) at versions 1, 2, 3 and 5, and 120 bags. Asserted against the owners' own tables: `_count == N`, the ids equal the source ids, no id twice, stored versions equal the source versions, `_alias/cases-read` names one index, stale events written during the build leave the newest version and a move the owner makes during the build ends at its new version, every bag has one journey with its legs and airlines, a second run gives the same result, and every table of both schemas is unchanged by the rebuilds (row counts and checksums). Not on the path: the gateway (no route to `/internal/**` by design), Compose and the identity-service (tokens are signed with a key made for the run); the stale events are handed to the indexer directly, the owner's move arrives through Kafka. Search side with a steerable stand-in (a race at a chosen page, failing and misbehaving pages): `RebuildFromSourceTest`, `JourneyRebuildTest`, `ReindexSafetyTest`. Owner side: `CaseExportApiTest` (#29), `BagExportApiTest` (#28) |
| AT-19 | #12 | capacity side: `RedisOfflineTest` (the service has no Redis; an unreachable Redis cannot change the 50-for-10 result); recovery side (no duplicate case with Redis down): `RedisDownTest` |
| AT-20 | #19 | |
| AT-21 | #19 | |
| AT-22 | #11, #18 | application level: `ApplicationRestartTest` (a second instance on the same database finds the committed case, history and events). The Minikube pod restart is #18 |
| AT-23 | #20 | |
| AT-24 | #20 | |

Rows marked "not tagged with its id yet" belong to issues that were delivered before this convention existed. Their behaviour is described in the issue's acceptance criteria, but the tests do not name the scenario. Tagging them is part of issue #21.
