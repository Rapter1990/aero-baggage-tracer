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
| BR-08 | #14 Notification Service | |

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
| AT-13 | #14 | |
| AT-14 | #11, #15 | `KafkaOutageTest` (a real broker is paused during a move: it commits, the events stay pending, and are published in order once, after the broker is back); `RecoveryCaseApiTest` (case level). The journey-service's outbox is #15's |
| AT-15 | #9, #8 | not tagged with its id yet |
| AT-16 | #7 | `GatewaySecurityTest` |
| AT-17 | #16 | |
| AT-18 | #13 | |
| AT-19 | #12 | capacity side: `RedisOfflineTest` (the service has no Redis; an unreachable Redis cannot change the 50-for-10 result); recovery side (no duplicate case with Redis down): `RedisDownTest` |
| AT-20 | #19 | |
| AT-21 | #19 | |
| AT-22 | #11, #18 | application level: `ApplicationRestartTest` (a second instance on the same database finds the committed case, history and events). The Minikube pod restart is #18 |
| AT-23 | #20 | |
| AT-24 | #20 | |

Rows marked "not tagged with its id yet" belong to issues that were delivered before this convention existed. Their behaviour is described in the issue's acceptance criteria, but the tests do not name the scenario. Tagging them is part of issue #21.
