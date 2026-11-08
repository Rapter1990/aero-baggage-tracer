# Analysis Report: Issue #4 - Define Common Module

| | |
|---|---|
| Backlog item | #4 (`docs/issues/GITHUB_ISSUES.md`) |
| GitHub issue | [#7](https://github.com/Rapter1990/aero-baggage-tracer/issues/7) (title `Issue #4 : Define Common Module`) |
| Branch / PR | `feature/4/define-common-module` / `PR#4 : Define Common Module` |
| Labels | `[module:common] [priority:p0]` |
| Depends on | #1 (Maven bootstrap, done) |
| Spec sections | §4, §7, §8, §10, §11, §14, §15 |
| Produced by | `/analyze-case` |

> Numbering note: backlog numbers and GitHub numbers differ because pull requests share the GitHub numbering. Backlog #7 is the API Gateway; GitHub #7 is this issue.

## 1. What the case study requires of `common-module`

Spec §4: *"Versioned API error contracts, event envelopes, shared security primitives; no JPA entities."*

## 2. Requirement coverage

| Spec requirement | Source | In Issue #4 scope? |
|---|---|---|
| RFC 7807 `ProblemDetail`, stable error contract (409 duplicate bagTag, 409 illegal transition) | §5.1, §7, BR-05; AT-02, AT-10 | Yes |
| `X-Correlation-Id` header on all APIs, propagated between services | §7, §10, §14 | Yes (filter, MDC, interceptor) |
| Event envelope: `eventId, eventType, schemaVersion, aggregateId, occurredAt, publishedAt, correlationId, payload` | §8 | Yes |
| Six topics with keys (see section 3) | §8 | **Partly**: only one topic is named, the rest elided with "..." |
| Roles `PASSENGER, AGENT, DISPATCHER, ADMIN` | §11 | Yes |
| JWT validation: signature, expiration, issuer, audience | §11; AT-16 | **Partly**: issuer/audience only |
| Never log JWTs or raw passenger data | §11, §14 | Yes (log masking) |
| `@HttpExchange` clients with correlation propagation | §10 | Yes (interceptor) |
| No JPA/Hibernate | §4 | Yes |
| >= 80% line coverage on domain/application code | §15 | Yes |

## 3. Topic contracts to put in common (§8)

| Topic | Key | Producer | Main consumer(s) |
|---|---|---|---|
| `baggage.scan.received.v1` | `bagTag` | journey-service | risk-streams-service |
| `baggage.scan.anomaly.v1` | `bagTag` | risk-streams-service | recovery-service |
| `baggage.risk.changed.v1` | `bagTag:legId` | risk-streams-service | recovery-service |
| `recovery.case.changed.v1` | `caseId` | recovery-service | search-service, notification-service |
| `recovery.capacity.changed.v1` | `resourceId` | capacity-service | recovery-service |
| `baggage.dead-letter.v1` | `eventId` | failed-event handler | operators |

## 4. Gaps found in the current Issue #4

1. **Incomplete topic list.** "all topics in §8" names one topic. Use the table above as the checklist.
2. **Payload DTOs unspecified.** Suggested records: `ScanReceived`, `ScanAnomaly`, `RiskChanged`, `CaseChanged`, `CapacityChanged`, `DeadLetter`. They must carry the `LATE_EVENT` flag and the `SCANNER_ANOMALY` type.
3. **Shared enums missing.** The spec's `HIGH` risk, `LATE_EVENT`, `SCANNER_ANOMALY` and recovery states (`OPEN, ASSESSING, RESERVED, IN_TRANSIT, RESOLVED, CLOSED_AS_UNRECOVERABLE, CANCELLED`) are used by several services; define them once here.
4. **JWT scope undecided.** Section 11 requires signature, expiry, issuer and audience validation at the gateway and at resource services. Decide whether common provides all four checks or only issuer/audience. AT-16 (expired token gives 401) depends on this.
5. **`ProblemDetail` fields undecided.** Settle `errorCode`, `correlationId` and `timestamp` properties so the 401/403/409 contracts are identical across services.
6. **No acceptance criterion for PII masking.** Add one for the log masker (JWTs and passenger data never appear in logs).

## 5. Proposed scope additions

- Full six-topic constants with key conventions.
- Shared enums: risk level, recovery state, anomaly type, ingestion status.
- One payload record per topic.
- Explicit decision on JWT signature/expiry validation location.
- Acceptance criterion: log masker removes JWTs and passenger references (unit-tested).

## 6. Proposed package layout

| Package (`com.aerotrace.common`) | Contents |
|---|---|
| `error` | `ErrorCode` enum, `BaseExceptionHandler` (`@RestControllerAdvice`, returns `ProblemDetail`) |
| `event` | `EventEnvelope<T>`, JSON config, payload records, `Topics` constants |
| `security` | `Role`, `JwtClaims`, issuer/audience validator, `SecurityContextHelper` |
| `correlation` | `X-Correlation-Id` constant, servlet filter (MDC), `RestClient`/`@HttpExchange` interceptor |
| `logging` | `LogMasker` |

## 7. Test plan

- Envelope serialization round-trip and schema-version handling.
- `ProblemDetail` shape for each stable error code.
- Correlation filter sets and clears MDC; interceptor forwards the header.
- Issuer/audience validator (valid, wrong issuer, wrong audience, expired if in scope).
- Log masker.
- Dependency check: no `hibernate`/`jpa` in `mvn dependency:tree`.
- JaCoCo >= 80% line coverage.

## 8. Downstream impact

Issue #4 is a dependency of #8 (Identity), #9 (Journey), #10 (Risk Streams), #11 (Recovery), #12 (Capacity), #13 (Search), #14 (Notification), #15 (Kafka infrastructure) and #7 (API Gateway). Changing a contract after those start is expensive, so the topics, payloads and error codes above should be settled first.

## 9. Related acceptance tests

- AT-02: duplicate bagTag returns a stable 409 contract.
- AT-10: illegal transition returns 409.
- AT-16: expired JWT returns 401, unauthorized role returns 403.

## 10. Open decisions

- Does common include JWT signature and expiry validation, or only issuer/audience?
- Final `ErrorCode` list.
- Does the 401/403 body use `ProblemDetail` too (recommended: yes)?

## 11. Method and limits

- Source: `AeroTrace_Senior_Java_Microservices_Case_Study.pdf`, read from git history (commit `d4b9646`); it is not in the working tree and must not be committed.
- Compared against `docs/issues/GITHUB_ISSUES.md` on `origin/main`.
- Keyword search was used to check coverage of the other backlog items; BR-08 and `outbox_pending_count` were not verified in detail.
- The `product-owner` agent was not used; this report is the analysis of a single pass.
