# AeroTrace – GitHub Issues Backlog

Derived from the AeroTrace case study; its business rules and acceptance tests are in `docs/requirements/`. Each `##` section is one issue (title = heading).
Suggested labels in `[brackets]`. Section numbers (§), BR-xx and AT-xx refer to the case study; their exact wording and the owning issue of each are in `docs/requirements/`.

**Suggested build order:** #1 → #2 → #3 → #4 → #5 → #6 → #8 → #7 → #9/#10/#11 (parallel) → #12 → #28/#29 → #13 → #14–#20 (cross-cutting, start early) → #21–#22.

---

## #1 Bootstrap Maven multi-module project (Java 25, Spring Boot)
`[setup] [priority:p0]`

**Description**
Create the parent POM and module skeleton for all services so every other issue has a place to land.

**Scope**
- Parent `pom.xml` (packaging `pom`), Java 25, Spring Boot + Spring Cloud BOM, dependency/plugin management.
- Modules: `common-module`, `discovery-server`, `config-server`, `api-gateway`, `identity-service`, `journey-service`, `risk-streams-service`, `recovery-service`, `capacity-service`, `search-service`, `notification-service`.
- Formatter plugin (e.g. Spotless) + JaCoCo + Surefire/Failsafe config; `.editorconfig`, `.gitignore`.

**Acceptance criteria**
- [ ] `mvn -q verify` passes on an empty skeleton with Java 25.
- [ ] Formatting check fails the build on violations.
- [ ] No secrets in the repository.

---

## #2 Define AI agent roles and project skills (.claude)
`[tooling] [docs] [priority:p0]` · Depends on #1

**Description**
Define a roster of role-based Claude Code subagents and reusable skills so work on AeroTrace is split between focused specialists (product, architecture, contracts, data, security, testing, DevOps, QA) with one orchestrating engineer, instead of one general-purpose agent doing everything.

**Scope**
- `.claude/agents/`: `product-owner`, `architect`, `principal-engineer` (delegation map), `api-contract-engineer`, `kafka-streams-engineer`, `database-reviewer`, `security-reviewer`, `code-reviewer`, `test-engineer`, `devops-engineer`, `manual-qa-tester`.
- `.claude/skills/`: `analyze-case`, `architecture-review`, `implement-feature`, `run`, `verify-project`, `create-issues`, `create-pr`.
- Each agent: single responsibility, minimal tool list, explicit model, project-specific rules (service-owned schemas, `ApiError`/`ApiResponse`, `*.v1` events, no JPA in `common-module`, no secrets).
- Reviewer agents are read-only; only implementers may edit files.
- Short "Agents" section in the README with the roster and delegation map.

**Acceptance criteria**
- [ ] Every agent and skill has valid frontmatter (`name`, `description`, `tools`, `model` for agents).
- [ ] `principal-engineer` lists every other agent in its delegation map.
- [ ] `implement-feature` and `verify-project` run end to end on the bootstrap skeleton.
- [ ] No secrets or case-study content are copied into the agent files.

---

## #3 Define GitHub automation scripts and skills (create-issues, create-pr)
`[tooling] [docs] [priority:p1]` · Depends on #2

**Description**
Turn the two PowerShell scripts under `docs/issues/` into project skills so issues and pull requests are always created through one defined command, instead of ad-hoc `gh` calls.

**Scope**
- `.claude/skills/create-issues/SKILL.md` wrapping `docs/issues/create-issues.ps1` (`-DryRun`, `-Repo`, `-File`); issue titles follow `Issue #N : Title`.
- `.claude/skills/create-pr/SKILL.md` wrapping `docs/issues/create-pr.ps1` (`-DryRun`, `-Draft`, `-Base`, `-Branch`); PR titles follow `PR#N : Title` and the body contains `Closes #<GitHub issue>`.
- Skills run the dry run first and ask for confirmation before creating anything on GitHub.
- `implement-feature` references `create-pr` for its PR step; README and `GITHUB_ISSUES.md` numbering updated (later issues shift by one).

**Acceptance criteria**
- [ ] Both skills have valid frontmatter (`name`, `description`) and name the exact command to run.
- [ ] `-DryRun` of each script is documented as the mandatory first step.
- [ ] Backlog numbering and all `Depends on` references are consistent after the shift.

---

## #4 Define Common Module
`[module:common] [priority:p0]` · Depends on #1 · Spec §4

**Description**
Shared library with versioned contracts and security primitives. **Must not contain JPA entities.**

**Scope**
- `ApiError` (error) and `ApiResponse` (success) envelopes + shared `@RestControllerAdvice` base, stable error codes (e.g. `BAG_TAG_DUPLICATE`, `ILLEGAL_TRANSITION`, `CAPACITY_EXCEEDED`).
- Event envelope record: `eventId, eventType, schemaVersion, aggregateId, occurredAt, publishedAt, correlationId, payload` + JSON (de)serialization config.
- Event payload DTOs / topic-name constants for all topics in §8 (`baggage.scan.received.v1`, …).
- Shared security primitives: roles enum (`PASSENGER, AGENT, DISPATCHER, ADMIN`), JWT claim names, issuer/audience validator, `SecurityContext` helper.
- Correlation-ID support: `X-Correlation-Id` constant, servlet filter / MDC, `RestClient`/`@HttpExchange` interceptor for propagation.
- Log-masking utilities (never log JWTs or passenger PII).

**Acceptance criteria**
- [ ] Published as an internal Maven artifact consumed by all services.
- [x] Envelope serialization round-trip and schema-version tests.
- [x] Unit tests ≥ 80% line coverage; no dependency on JPA/Hibernate.

---

## #5 Define Eureka Server (discovery-server)
`[module:discovery] [priority:p0]` · Depends on #1 · Spec §4, §13

**Description**
Service registration and discovery for the development profile.

**Scope**
- Spring Cloud Netflix Eureka Server, standalone dev profile, health endpoint, Dockerfile.
- Client config template for all services (instance id, lease renewal, health-check URL).
- **Authenticated registry**: HTTP Basic (`EUREKA_USER` / `EUREKA_PASSWORD`, no default password) on the registry API and dashboard; only `/actuator/health` is open. Clients pass the credentials in the service URL (`http://user:password@host:8761/eureka/`), never stored in the repo. Without this anyone who can reach port 8761 could register a fake service and receive forwarded bearer tokens.
- The registry exports traces and metrics over OTLP to the OpenTelemetry Collector (Tempo, Prometheus, Grafana), like every other service; its traces are mostly the Eureka protocol (registrations, heartbeats, fetches).
- Document how discovery works in Compose (Eureka) vs Minikube (Kubernetes Services/DNS) and when each is used.

**Acceptance criteria**
- [ ] Services register and appear in the Eureka dashboard in Compose.
- [x] Health check usable by Compose `healthcheck`.
- [x] Registry reads and registrations are refused (401) without valid credentials; clients with credentials register (tested).
- [ ] Short doc section comparing Compose vs Minikube discovery.

---

## #6 Define Config Server
`[module:config] [priority:p1]` · Depends on #1

**Description**
Centralized, profile-based configuration (dev / compose / k8s). *Note: not in the case-study module list (§4); included as requested. Mark optional if K8s ConfigMaps/Secrets are deemed sufficient.*

**Scope**
- Spring Cloud Config Server (native or Git backend), per-service config files, profiles `dev`, `compose`, `k8s`.
- Airport-specific thresholds (e.g. 20‑min risk threshold) and Kafka/Redis/DB endpoints externalized.
- Secrets are **not** stored in the repo; use env vars / K8s Secrets. Eureka URLs in the config repo carry `${EUREKA_USER}` / `${EUREKA_PASSWORD}` placeholders that each client resolves from its own environment (the config server never sees or sends the password).
- The config server itself exports traces and metrics over OTLP to the OpenTelemetry Collector (Tempo, Prometheus, Grafana), and the shared config-repo template gives every service the same OTLP, sampling and metric-tag settings (placeholders the client resolves).
- Clients use `spring.config.import` with fail-fast and retry.

**Acceptance criteria**
- [ ] A service boots with config pulled from the server in Compose.
- [ ] Refresh of a non-secret property works without restart (or documented limitation).
- [ ] Decision recorded in an ADR (Config Server vs ConfigMaps).

---

## #7 Define API Gateway
`[module:gateway] [priority:p0]` · Depends on #4, #5, #8 · Spec §4, §7, §11

**Description**
Single external entry point under `/api/v1`, built on the **servlet stack** (Spring Cloud Gateway Server MVC, Spring MVC, Spring Security). The module is blocking/imperative: **no WebFlux, no Reactor Netty, no `Mono`/`Flux`** in the gateway's own code or dependencies.

**Scope**
- Spring Cloud Gateway **Server MVC** (`spring-cloud-starter-gateway-server-webmvc`) routes to all services via Eureka (`lb://`).
- Registers itself with the Eureka server (discovery-server, #5) as `API-GATEWAY` (instance id `api-gateway:<host>:<port>`, 10 s lease renewal) and fetches the registry to resolve `lb://` routes. `EUREKA_URL` / `EUREKA_INSTANCE_HOSTNAME` select the server and the advertised host per environment.
- JWT verification with Spring Security's servlet resource server: the signature is verified with the identity-service **public key** (`java.security.PublicKey`, obtained from its JWKS so several active `kid`s work), plus `exp`, `iss`, `aud`; 401 for missing/invalid, 403 for forbidden.
- Token forwarding to downstream services; Redis-backed rate limiting as a servlet filter (blocking Spring Data Redis, token bucket). Redis runs with a password (`REDIS_PASSWORD`, no default in the gateway) in both Compose files.
- Generate/propagate `X-Correlation-Id`; pass `Idempotency-Key`. Reuse the servlet `CorrelationIdFilter` and `ApiErrorWriter` from `common-module`.
- ApiError-formatted gateway errors; OpenTelemetry tracing (W3C `traceparent` continued and propagated to downstream services) and Micrometer metrics, both exported over OTLP to an OpenTelemetry Collector: metrics are scraped by Prometheus (no admin token needed) and traces are stored in Tempo; Logs go to the same collector and on to Loki, each line tagged with its trace id; Grafana shows three provisioned dashboards: service metrics, traces (tables of requests, failures and calls with a service dropdown and links that open the full trace) and logs per service, with trace↔log links in both directions; a fourth dashboard shows JaCoCo test coverage per module (a script pushes the reports to a Pushgateway that Prometheus scrapes) (`observability/`, started by both Compose files). Downstream calls time out (2 s connect, 30 s read) instead of holding gateway threads.
- Swagger UI (springdoc) at `/swagger-ui.html` with a "Select a definition" dropdown listing every service; each service's OpenAPI document is fetched through the gateway (`/service-docs/<service>` → the service's `/v3/api-docs`). Can be switched off with `DOCS_ENABLED=false`.

**Acceptance criteria**
- [x] `API-GATEWAY` registers with Eureka and shows as `UP` (automated test against a Eureka stub; also checked against a running discovery-server).
- [x] Swagger UI at the gateway lists each service's endpoints through a dropdown (the services must expose `/v3/api-docs`).
- [x] AT-16: expired JWT → 401; wrong role → 403.
- [x] Correlation ID present on every response and downstream request.
- [x] A trace context is started or continued at the gateway and sent downstream; a slow downstream is cut off at the read timeout (both tested).
- [x] Gateway metrics and span-derived request metrics reach Prometheus through the collector, and the Grafana dashboard queries them (verified with real containers).
- [x] Gateway traces are stored in Tempo and can be found by TraceQL in Grafana, including a trace the caller started (verified with real containers).
- [x] Rate-limit returns 429 with an ApiError (verified against a real Redis).
- [x] Redis requires a password: the rate limiter authenticates with it, and clients without or with a wrong password are refused (verified against a real Redis).
- [x] Gateway token-forwarding integration test (real HTTP hop to a downstream stub).
- [x] Servlet stack only: no WebFlux or Reactor Netty dependency in `api-gateway`; no `Mono`/`Flux` in its code.

---

## #8 Define Identity Service
`[module:identity] [security] [priority:p0]` · Depends on #4 · Spec §4, §11

**Description**
Issues signed JWT access tokens and owns users/roles.

**Scope**
- Seed test users for `PASSENGER`, `AGENT`, `DISPATCHER`, `ADMIN`; passenger ↔ pseudonymous `passengerRef` binding.
- `POST /auth/token`, JWKS endpoint, asymmetric signing (RS256/ES256) with the JDK's `java.security` types: sign with a `PrivateKey`, publish the matching `PublicKey` (as JWK), **key rotation** (multiple active `kid`s). Servlet stack (Spring MVC), like the gateway.
- Claims: `iss`, `aud`, `sub`, `roles`, `passengerRef`, `exp`.
- Authorization policy definitions (who can transition cases, manage capacity, change risk policies).
- Own Liquibase changelog + PostgreSQL schema; password hashing (BCrypt/Argon2); immutable audit of auth events.
- Observability like the gateway, config server and discovery server: `spring-boot-starter-opentelemetry`, traces and OTLP metrics exported to the OpenTelemetry Collector (Tempo, Prometheus, Grafana) using the shared config-repo template settings; the compose services get the collector endpoints. Tokens, passwords and other credentials must never appear in span attributes or metric labels.

**Acceptance criteria**
- [ ] Tokens validate at gateway and resource services; rotation does not invalidate unexpired tokens.
- [ ] No JWT or credentials in logs.
- [ ] Traces and metrics of the identity-service reach Tempo and Prometheus (same checks as the gateway), and none contains a token or credential.
- [ ] Authorization matrix test (role × endpoint).
- [ ] OpenAPI documents 401/403 examples.

---

## #9 Define Journey Service
`[module:journey] [priority:p0]` · Depends on #4, #8 · Spec §5.1, §7, §9

**Description**
Baggage registration, itinerary snapshots, scan ingestion API and authoritative journey state.

**Scope**
- `POST /bags` (bagTag unique → 409 `BAG_TAG_DUPLICATE`), validate IATA codes, leg chronology, minimum connection time.
- `GET /bags/{bagTag}/journey`: latest accepted state **and** immutable `scan_audit` history; passenger row-level ownership.
- `POST /scans` with `Idempotency-Key` → `202 QUEUED`; publish `baggage.scan.received.v1`.
- **Factory Method** for versioned scan handlers per protocol; **Adapter** for barcode/RFID/conveyor payloads.
- Normalize timestamps to UTC while retaining source timezone and device timestamp; flag `LATE_EVENT` beyond grace (BR-06).
- Tables: `baggage, itinerary, itinerary_leg, scan_audit, outbox_event, processed_event`; durable dedupe on `event_id`.
- Transactional outbox + scheduled relay (at-least-once).

**Acceptance criteria**
- [x] Bag registration validates the tag, IATA codes, leg chronology and minimum connection time; a duplicate tag is `409 BAG_TAG_DUPLICATE`, also under parallel requests.
- [x] A scan is accepted with `202 QUEUED` and queues `baggage.scan.received.v1`; the same `Idempotency-Key` never creates a second scan or event.
- [x] Times are stored in UTC with the source timezone kept; a scan beyond the grace is flagged `LATE_EVENT`; an older scan never replaces a newer state and stays in the history.
- [x] A passenger sees only their own bag's journey; staff see every bag.
- [x] Two replicas run correctly (no double-publish side effects).
- [x] Liquibase V1–V3 (see #16).
- [x] ≥ 80% coverage of domain/application code.

---

## #10 Define Risk Streams Service (Kafka Streams)
`[module:risk-streams] [kafka] [priority:p0]` · Depends on #4, #9 · Spec §5.2, §8

**Description**
Kafka Streams topology that computes connection risk and scan anomalies.

**Scope**
- Consume `baggage.scan.received.v1`; key/partition by `bagTag`.
- State store / KTable: latest legitimate checkpoint per bag.
- Risk: HIGH when departure < threshold (default 20 min, configurable per airport) and no loading confirmation (BR-02); downgrade/clear on valid confirmation (AT-08).
- Detect impossible transitions without discarding raw history; 3 rejected transitions in a 5-min **event-time** window → `SCANNER_ANOMALY` (BR-03).
- Document & test grace period, window boundaries, suppression, late-event behavior.
- Publish `baggage.risk.changed.v1` (key `bagTag:legId`) and `baggage.scan.anomaly.v1`.
- Retry / dead-letter / poison-message policy → `baggage.dead-letter.v1`; durable deduplication (EOS ≠ end-to-end idempotency).
- Reproducible late / duplicate / out-of-order fixture datasets.

**Acceptance criteria**
- [x] AT-06, AT-07, AT-08 (wording checked against `docs/requirements/ACCEPTANCE_TESTS.md`; covered by `AcceptanceScenariosTest`). The numeric defaults (20 min threshold, 5 min window, grace) are still proposed values.
- [x] `TopologyTestDriver` tests incl. grace-boundary cases.
- [x] Metrics `stream_lag_seconds`, `baggage_risk_high` (unit-tested; `baggage.risk.changed.v1` also seen end to end on a running Compose stack).

---

## #11 Define Recovery Service
`[module:recovery] [priority:p0]` · Depends on #4, #10, #12 · Spec §5.3, §10

**Description**
Recovery case state machine, handler assignment and action orchestration.

**Scope**
- `POST /recovery-cases` (one active case per bag+leg; parallel requests produce one — BR-01, AT-09) via unique partial index + idempotent handling.
- `PATCH /recovery-cases/{id}/transitions` with `expectedVersion` (optimistic lock); states `OPEN → ASSESSING → RESERVED → IN_TRANSIT → RESOLVED`, terminals `CLOSED_AS_UNRECOVERABLE`, `CANCELLED`; illegal move → 409 (AT-10); RESOLVED cannot reopen (BR-07).
- **State pattern** with guard conditions; `state_transition` records who/when/why (BR-05).
- **Strategy pattern** to choose action: expedited transfer / reroute to next flight / hold at service desk (airline policy, time left, capacity).
- **Saga/compensation**: call capacity-service via typed `@HttpExchange` client (timeouts, correlation propagation, explicit fallback); release holds on timeout (AT-12); document failures between steps.
- Consume `baggage.risk.changed.v1`, `baggage.scan.anomaly.v1`, `recovery.capacity.changed.v1`.
- Transactional outbox → `recovery.case.changed.v1` (AT-14, AT-22).
- RBAC: only AGENT/DISPATCHER may transition; ADMIN changes risk policy; Redis-cached policy with TTL + invalidation.
- `GET /recovery-cases?state=OPEN&page=0&size=20` (spec §7): paged list with deterministic sorting.
- `Idempotency-Key` on `POST /recovery-cases` and on transitions: a retry returns the original result and has no second effect.
- Tables: `recovery_case`, `state_transition`, `outbox_event`, `processed_event` (durable dedup of the consumed events; business effects stay idempotent, Kafka EOS is not enough).
- Handler assignment: a case can be assigned to an AGENT/DISPATCHER; the assignment is recorded in `state_transition` (who/when/why).
- Metrics `recovery_resolution_seconds` and `outbox_pending_count`.

**Requirement coverage** (wording in `docs/requirements/`)
- BR-01, BR-05, BR-07; AT-09, AT-10, AT-12 are proven in this issue.
- AT-14 is proven here with a real Kafka broker (`KafkaOutageTest`). AT-22 is proven here at application level (`ApplicationRestartTest`); the same check with a real Minikube pod restart needs the manifests of #18.

**Acceptance criteria**
- [x] BR-01: at most one active case per bag + leg, also under parallel requests and with Redis down (AT-09; `RecoveryCaseApiTest`, `RedisDownTest`).
- [x] Redis caches the recovery policy (`GET/PUT /api/v1/recovery-policy`, PUT admin only) with an explicit TTL and eviction on change; PostgreSQL is authoritative and degraded mode is documented in `docs/adr/0001-redis-policy-cache.md`.
- [x] BR-05: every move follows the state machine and records who/when/why; an illegal move is `409` (AT-10). Assignment of a case to a handler is recorded the same way (`PUT .../assignment`).
- [x] BR-07: a RESOLVED case cannot be reopened; a new leg needs a new case key.
- [x] AT-09, AT-10, AT-12 (`RecoveryCaseApiTest`, `RecoveryStateMachineTest`, `CapacitySagaTest`).
- [x] AT-14: Kafka stopped during a move, the move commits, and the events are published in order when the broker is back, at least once: an attempt the outage cut off may land twice with the same event id, which consumers dedupe (`KafkaOutageTest`, a real broker).
- [x] AT-22 at application level: a restarted instance keeps the committed case, history and events and carries on (`ApplicationRestartTest`). The restart of a real Minikube pod is demonstrated in #18.
- [x] `GET /recovery-cases` pages and sorts deterministically; `Idempotency-Key` retries (create and move) have no second effect (`CaseOperationsApiTest`).
- [x] Unit tests for strategies, guards, compensation.
- [x] Every test of a BR/AT starts its `@DisplayName` with its id; `docs/requirements/TRACEABILITY.md` is updated.

---

## #12 Define Capacity Service
`[module:capacity] [priority:p0]` · Depends on #4, #8 · Spec §5.3, BR-04

**Description**
Finite recovery cart / flight / bag-slot capacity with atomic reservations.

**Scope**
- `POST /capacity/reservations`, `DELETE /capacity/reservations/{id}` (release/compensation), admin-only resource management.
- Contract the recovery-service (#11) already calls: the caller chooses `reservationId` in the `POST` body (`{reservationId, caseId, bagTag, legId, slots}`), answers `201` with `ApiResponse{reservationId, status}` or `409 CAPACITY_EXCEEDED`; `DELETE /{id}` answers `204`, or `404` when there is no such hold (treated as released). Releases made by the recovery-service sweeper carry no user token, so release must be allowed for service callers (or the identity-service must issue service tokens).
- Atomic reservation in PostgreSQL (conditional `UPDATE … WHERE remaining > 0` or row lock); **DB is authoritative**, Redis only an optional cache/hint.
- Reservation TTL / provisional hold expiry job. Contract with the recovery-service: a hold it made stays valid while its case is `RESERVED` or `IN_TRANSIT`; the recovery-service releases it (`DELETE`) when the case is `RESOLVED`, `CANCELLED` or `CLOSED_AS_UNRECOVERABLE`. An expiry job may remove only holds that were never confirmed by a case move, so it must not take the slot of a bag that is on its way.
- Tables: `capacity_resource`, `capacity_reservation`, outbox; publish `recovery.capacity.changed.v1` (key `resourceId`).
- Metric `capacity_reservation_conflicts_total`; idempotent reservations via `Idempotency-Key`.

**Acceptance criteria**
- [x] AT-11: 50 concurrent requests for 10 slots → exactly 10 succeed (race test).
- [x] AT-19: Redis offline → no overbooking (the service has no Redis dependency; PostgreSQL alone decides).
- [x] 409 conflict contract documented in OpenAPI.

---

## #13 Define Search Service (Elasticsearch)
`[module:search] [priority:p1]` · Depends on #4, #8, #9, #10, #11, #15, #17, #28, #29 (and #19 for the Grafana criterion) · Spec §5.4, §12 · ADR `docs/adr/0004-search-read-models-and-rebuild.md`

**Description**
Elasticsearch read models for journey and case search. Derived data: it can be dropped and rebuilt from the owning services (AT-18) and never writes to them.

**Scope**
- Indexer: consume `recovery.case.changed.v1` (revision 2), `baggage.bag.registered.v1`, `baggage.scan.received.v1` and `baggage.risk.changed.v1`; `cases` (`_id = caseId`, external version = payload `version`) and `journeys` (`_id = bagTag`, scripted upsert guarded by event time) indexes from a strict index template; an Elasticsearch `409` is a duplicate, not an error; offset committed after the bulk is acknowledged; poison events go to `baggage.dead-letter.v1`; the envelope `correlationId` is restored, never generated.
- `GET /api/v1/search/bags`: optional AND-combined filters `bagTag`, `airport`, `airline`, `flight`, `riskLevel`, `caseState`; `size` default 20, maximum 100 (out of range is `400`); sort `updatedAt desc` then a stored `docId`; opaque signed cursor over a point-in-time (`search_after`); `ApiResponse{items, size, nextCursor, hasMore}`.
- Rebuild/reconcile: `POST /api/v1/admin/search/reindex` (ADMIN, `Idempotency-Key`, `202` + `jobId`, `409 REINDEX_IN_PROGRESS`) and `GET .../reindex/{jobId}`. Source: the export endpoints of #28 and #29 and the compacted risk topic (#15); only `GET`s and consumption, source untouched. Dual-alias swap (`*-read`, `*-live`), atomic `_aliases`, old index deleted after the point-in-time keep-alive.
- Indexing lag: `search.index.lag`, `search.index.lag.events`, `search.index.events`, `search.index.delay`; `GET /api/v1/search/index/status` (DISPATCHER, ADMIN).
- `GET /api/v1/reports/airport/{iata}?from=&to=`: hourly UTC buckets over `[from, to)` (maximum 31 days, zero-filled) with risky bags, resolved recoveries, retries and mean resolution time as defined in the ADR; `indexedThrough` and `lagSeconds` in the response; unknown airport with no data is `200`.
- Gateway routes `/api/v1/search/**` and `/api/v1/admin/search/**`; roles: `AGENT`, `DISPATCHER`, `ADMIN` search and report, `PASSENGER` always `403` (no `passengerRef` in the index); new error codes `SEARCH_UNAVAILABLE` (503), `REINDEX_IN_PROGRESS` (409).
- No JPA, no datasource; Spring Data Elasticsearch repositories for the documents plus `ElasticsearchOperations` and the Java API client for what a repository cannot express (external versioning, scripted upserts, aliases); nothing Elasticsearch-related in `common-module`. Elasticsearch in both Compose files (image 9.5.6, Elastic's newest release, as it publishes no `latest` tag; security on, dev-only default password, port 9200 published).
- `docs/apis/SEARCH-API.md`, a Search folder in the Postman collection.

**Requirement coverage**
- AT-18 (`docs/requirements/ACCEPTANCE_TESTS.md`): "Rebuild Elasticsearch from source data with no duplicate documents." The test seeds more cases than one export page (N > 100) with different versions, rebuilds, and asserts: `_count == N`, the set of ids equals the source ids, no id appears twice, stored versions equal source versions, a second run gives the same result, `_alias/cases-read` names exactly one index, a stale-version event racing the rebuild leaves the newest version, and the source row counts and checksums are unchanged.
- No business rule BR-xx is owned by this issue. AT-15, AT-16, AT-20 and AT-21 are owned elsewhere (#8, #7, #19); search adds its own role-matrix tests (401 and 403 contract, `PASSENGER` refused).

**Acceptance criteria**
- [x] AT-18 with an Elasticsearch Testcontainer (pinned image), as described above.
- [x] Idempotent indexer: the same `recovery.case.changed.v1` twice, or an older version after a newer one, leaves one document at the newest version; replay and snapshot overlap give the same documents.
- [x] One test per filter and a combined-filter test; `search_after` paging over a fixed data set has no duplicates and no gaps; forged and expired cursors are rejected.
- [x] Alias-swap rebuild without downtime: searches during a rebuild return results without error, and the read alias moves only when the new index is complete and its counts match.
- [x] Report: validation (IATA, `from <= to`, maximum range), zero-filled hours, `null` mean when nothing resolved, definitions as in the ADR.
- [x] Roles: expired or missing token `401`, wrong role `403`, `PASSENGER` refused on every search and report endpoint, reindex `ADMIN` only.
- [x] Lag metric exposed and visible in Grafana (the "AeroTrace - Elasticsearch" dashboard; its queries were run against a live Prometheus and Grafana with the search-service indexing).
- [x] `docs/requirements/TRACEABILITY.md` updated (AT-18 Test column).

---

## #14 Define Notification Service
`[module:notification] [priority:p2]` · Depends on #4, #11 · Spec BR-08, §10

**Description**
Idempotent in-app notifications and delivery status; external provider mocked.

**Scope**
- Consume `recovery.case.changed.v1` (**Observer / domain events**).
- Unique `(recipient, eventId, template)` constraint (BR-08, AT-13); delivery status + retry with backoff.
- Mock provider adapter; no PII in logs.
- `GET` endpoints for a user's notifications (ownership enforced).

**Acceptance criteria**
- [ ] AT-13: repeated outbox publication → one notification.
- [ ] Retry/DLQ behavior tested.

---

## #15 Kafka infrastructure, event contracts & replay fixtures
`[kafka] [priority:p1]` · Depends on #4 · Spec §8

**Scope**
- Topic definitions (partitions, key, retention) for all six topics; Compose + K8s provisioning.
- Retry/backoff, dead-letter publisher, poison-message policy; operator inspection tooling for `baggage.dead-letter.v1`.
- Example replay dataset (late, duplicate, out-of-order) + replay script.
- ADR: exactly-once business effects (outbox + `processed_event`).
- Search rebuild support (ADR 0004, #13): `baggage.risk.changed.v1` as `cleanup.policy=compact,delete` with a long retention so the search-service can replay it from offset 0; the new topic `baggage.bag.registered.v1` (key `bagTag`, added to `Topics.ALL`) with partitions, retention and Compose + K8s provisioning; contract/serialization tests for the new payload revisions (`CaseChangedPayload` revision 2, `baggage.bag.registered.v1`).

**Acceptance criteria**
- [ ] AT-14: stop Kafka mid-transition, restart, committed event is replayed.
- [ ] Contract/serialization compatibility tests.

---

## #16 Liquibase migrations & zero-data-loss demo
`[database] [priority:p1]` · Spec §9

**Scope**
- One changelog per service/schema; safe to re-run; index choices documented.
- **V1** base tables + seed; **V2** add nullable `scan_source` + backfill; **V3** enforce NOT NULL after validation.
- Unique constraints: `bag_tag`, source `event_id`, active recovery case natural key; `version` columns.
- Demo script: legacy rows → V2/V3 → compare checksums + row counts; document rollback strategy and limits for destructive changes.
- ADR: data migration strategy.

**Acceptance criteria**
- [ ] AT-17 automated with Testcontainers PostgreSQL.
- [ ] Rollback recipe documented.

---

## #17 Docker & Docker Compose runtime
`[devops] [priority:p1]` · Spec §13

**Scope**
- Multi-stage Dockerfiles per service (non-root, layered jars, healthchecks).
- `docker-compose.yml`: PostgreSQL, Kafka, Redis, Elasticsearch, Eureka, (Config Server), Gateway, all services, plus observability stack (see #19).
- Sample seed data and one-command startup script.

**Acceptance criteria**
- [ ] `docker compose up` yields all-healthy stack.
- [ ] Reviewer can seed a journey and generate duplicate/late scans.

---

## #18 Kubernetes / Minikube manifests (or Helm)
`[devops] [k8s] [priority:p1]` · Depends on #17 · Spec §13

**Scope**
- Deployments, Services, ConfigMaps, Secrets (secret-management doc for Minikube), PVCs, probes, resource requests/limits.
- journey-service with 2 replicas; correctness during pod restart and rolling update (AT-22).
- Document port-forwarding, discovery and gateway routing in Minikube.

**Acceptance criteria**
- [ ] AT-22; rolling update with zero lost committed events.
- [ ] `kubectl apply` / `helm install` documented end-to-end.

---

## #19 Observability: Prometheus, Grafana, Loki, Tempo
`[observability] [priority:p1]` · Spec §14

**Scope**
- Already in place from #7: OpenTelemetry Collector (`observability/otel-collector.yml`) → Tempo (traces) and Prometheus (metrics) → Grafana in both Compose files, with the gateway, the config server and the discovery server exporting traces and metrics over OTLP and provisioned services and traces dashboards. Still open here: tracing across Kafka producers/consumers and the other services, service metrics/dashboards, alerts. Loki, the services' log shipping with trace ids, the log↔trace links and the "AeroTrace - Logs" dashboard are also already in place from #7 (gateway, config server, discovery server); the structured JSON log format below is still open.
- Micrometer metrics: `scan_ingest_total, scan_duplicates_total, baggage_risk_high, recovery_resolution_seconds, capacity_reservation_conflicts_total, stream_lag_seconds, outbox_pending_count`.
- Grafana dashboard (scan throughput, high-risk bags, case states, P95 latency, error rate, Kafka lag, JVM).
- Structured JSON logs → Loki with `traceId, spanId, correlationId, service, eventId` (no PII).
- OpenTelemetry tracing across gateway → services → Kafka producers/consumers → Tempo; Grafana log↔trace links.
- Alerts: high-risk surge, outbox backlog, prolonged stream lag, reservation conflicts.

**Acceptance criteria**
- [ ] AT-20, AT-21.
- [ ] Dashboard JSON + alert rules committed.

---

## #20 GitHub Actions CI/CD & SonarQube
`[ci-cd] [priority:p1]` · Spec §16

**Scope**
- PR workflow: Java 25 build, format check, unit + Testcontainers tests, JUnit/coverage publishing, SonarQube gate, Docker image build.
- Main workflow (after gate): version tags, push to configurable registry, deploy to Minikube (self-hosted runner or manual runbook).
- Dependency and image scanning; secret scanning; never deploy failing images.
- Rollback recipe + post-deploy smoke test (gateway, scan processing, Grafana metrics).

**Acceptance criteria**
- [ ] AT-23: failed quality gate → no deployment.
- [ ] AT-24: rollback + smoke test demonstrated.

---

## #21 Test strategy, security matrix & concurrency tests
`[testing] [priority:p1]` · Spec §15, §18

**Scope**
- JUnit 5/Mockito Given-When-Then tests; Spring Boot + Testcontainers (PostgreSQL, Kafka, Redis, Elasticsearch).
- Kafka Streams deterministic tests, grace-boundary tests.
- Security authorization matrix; gateway token forwarding; `@HttpExchange` timeout/error tests.
- Race tests: 50 parallel requests vs 10 slots; duplicate case creation race.
- JaCoCo 80% gate on domain/application code (exclude test/generated code).
- Traceability table mapping AT-01…AT-24 to tests.

**Acceptance criteria**
- [ ] Coverage report published in CI.
- [ ] Every AT-xx mapped to an automated test or documented demo.

---

## #22 Performance benchmark (100 scans/s)
`[performance] [priority:p2]` · Spec §17

**Scope**
- Load script: 100 scans/s sustained for 2 min; P95 read API < 400 ms after warm-up.
- Record machine configuration and methodology; publish results.

**Acceptance criteria**
- [ ] Reproducible script + results in `docs/performance.md`.

---

## #23 Documentation & submission artifacts
`[docs] [priority:p1]` · Spec §19, §22

**Scope**
- README (clone → run → seed → observe), architecture + sequence diagrams.
- OpenAPI specs with success/validation/conflict/forbidden examples; curl happy/failure paths; Postman collection.
- ADRs: state management, exactly-once business effects, Redis fallback/degraded mode, data migration, (Config Server).
- Grafana exports/screenshots, tracing evidence, Kafka replay dataset.

**Acceptance criteria**
- [ ] Reviewer can reproduce the "definition of done" scenario in §22 from the README alone.

---

## #24 (Optional) Advanced challenges
`[optional] [priority:p3]` · Spec §21

- Misconfigured airport clocks with provenance + chronology rejection.
- Multi-tenant airline isolation (gateway, service, index).
- Blue/green event schema v1 → v2 migration.
- Chaos experiment (Kafka outage / DB restart) with recovery timeline and integrity checks.

---

## #25 Rate limiting and lockout for the authentication endpoints
`[module:gateway] [module:identity] [security] [priority:p1]` · Follow-up of #8 (PR#8) · Depends on #7

**Description**
Each wrong password costs a BCrypt-12 hash and the only brake is the gateway's per-address rate limit (about 10 requests per second, shared by every client behind a reverse proxy). `login`, `register`, `forgot-password` and `reset-password` can be used for credential stuffing, account flooding and CPU exhaustion.

**Scope**
The work is split by who knows what: the gateway sees only addresses and paths, the identity-service owns accounts and password checks.

*Gateway* (`api-gateway`)
- Stricter, per-endpoint buckets for `/api/v1/auth/*`.
- Trusted-proxy configuration, so the client address is the real one behind a TLS reverse proxy and `X-Forwarded-For` cannot be forged.

*Identity-service* (`identity-service`)
- A per-target-account bucket for `login` and `forgot-password` (the gateway cannot see the username without reading the request body).
- Failed-login counter per typed name (known or not, stored only as a hash) with a temporary lockout of a fixed length, without letting an attacker extend it by trying again; the per-address limit is the gateway's bucket above. Password reset requests are limited per typed name the same way.
- Bounded concurrency for password hashing; metrics (outcome only) for refused attempts.

**Acceptance criteria**
- [x] 429 with the shared `ApiError` and `Retry-After` after the per-endpoint limit (gateway, tested against a real Redis) and the per-account limits (identity-service, tested over HTTP against a real PostgreSQL); the answer is the same for names that do not exist.
- [x] A forged `X-Forwarded-For` does not change the bucket unless the proxy is trusted (unit tests and tests against a real Redis, with and without a trusted proxy).
- [x] Repeated wrong passwords lock the name for a limited time (15 minutes by default, not extendable by trying again); the lock is audited once and refused calls are counted by reason.
- [x] Password hashing runs at most N at a time; a call that cannot get a slot in time gets a 429.
- [x] README documents the limits and how to change them ("Rate limits and lockout of the authentication endpoints").

---

## #26 Identity: session and account hardening
`[module:identity] [security] [priority:p2]` · Follow-up of #8 (PR#8)

**Description**
Gaps left in PR#8: refresh sessions slide forever, access tokens cannot be revoked before they expire, creating staff trusts the roles in a token that may be 15 minutes old, and registration answers 409 for existing usernames and emails.

**Scope**
- Absolute session lifetime: carry a session id or start time through refresh-token rotation and refuse after N days.
- Re-check the caller's current roles and enabled flag in the database for `USER_MANAGE` (staff creation); optionally a per-user `tokens_valid_after` so password reset or disabling a user invalidates access tokens immediately.
- Registration that does not reveal whether a username or email exists (generic answer plus email verification), once the notification-service (#14) can send mail.
- Restrict email addresses to a form that lower-cases identically in Java and PostgreSQL, and map CHECK violations to 400.
- Cap the number of active refresh tokens per user.

**Acceptance criteria**
- [ ] A session ends after the absolute limit even if it is refreshed continuously.
- [ ] A demoted or disabled ADMIN cannot create staff accounts.
- [ ] Registration answers identically for new and existing usernames and emails.
- [ ] Tests cover each of the above.

---

## #27 Hardened Docker Compose defaults
`[devops] [security] [priority:p2]` · Follow-up of #8 (PR#8) · Depends on #17

**Description**
`docker-compose.yml` publishes Eureka (8761), config-server (1111), Prometheus (9090), Grafana (3000) and the gateway (8080) on all interfaces with default credentials (`eureka`, `admin/grafana`). Anyone who can reach the host can register a fake `identity-service` in Eureka and receive login traffic. Images use floating tags, the identity-service runs `curl` only for its health check, and its database user needs `CREATE` on the database for the schema init SQL.

**Scope**
- Required secrets without defaults (`EUREKA_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`) or a documented, clearly marked development override; ports for internal tools published only when asked for.
- Pin base and infrastructure images by digest; non-root users, `read_only`, `cap_drop: [ALL]`, `no-new-privileges` for service containers; a JRE-native health check instead of `curl`.
- Create the `identity` schema with a one-time migration or admin user and run the service with a least-privilege database role.
- Run the Postman collection (`postman/`) with newman against the composed stack as a smoke test, including the gateway-to-identity path.

**Acceptance criteria**
- [ ] `docker compose up` fails fast without the required secrets, or starts with loopback-only ports when the development override is used.
- [ ] Images are pinned and containers run as non-root with a read-only filesystem where possible.
- [ ] The identity-service starts with a database role that cannot create objects outside its schema.
- [ ] The newman smoke test passes against the composed stack.

---

## #28 Journey: internal bag export and bag-registered event
`[module:journey] [priority:p1]` · Follow-up of #9 · Needed by #13 · ADR `docs/adr/0004-search-read-models-and-rebuild.md`

**Description**
The search-service must be rebuildable from the journey-service without reading its database (AT-18), and the journey data it filters on (airline, origin, destination) is in no event. The journey-service exports its own state and publishes the itinerary snapshot.

**Scope**
- `GET /internal/v1/export/bags?after={bagTag}&size=`: keyset-paged, read-only, bounded (`size` maximum 500); returns each bag with its legs (`legId`, `flightNumber`, `airline`, `origin`, `destination`, `departureAt`) and the latest accepted scan (`occurredAt`, `deviceSequence`, airport). Authenticated with a service token (like capacity's `X-Service-Token`); no gateway route; no PII beyond what the journey events already carry.
- Publish `baggage.bag.registered.v1` (key `bagTag`, via the existing outbox) when a bag is registered, with the itinerary snapshot per leg including `airline` (the validated carrier prefix of the flight number, documented as a rule). Additive: existing events and topics unchanged.
- Document both in `docs/apis/JOURNEY-API.md`; tests for paging (no gaps or repeats while rows are inserted), the service token, and the event contract.

**Acceptance criteria**
- [x] The export pages through more bags than one page with no gap and no repeat, and requires the service token (`401`/`403` otherwise); it writes nothing.
- [x] Registering a bag queues `baggage.bag.registered.v1` once (outbox, idempotent), with the snapshot of every leg.
- [x] Existing journey tests and contracts still pass; `JOURNEY-API.md` updated.

---

## #29 Recovery: internal case export and case-changed revision 2
`[module:recovery] [priority:p1]` · Follow-up of #11 · Needed by #13 · ADR `docs/adr/0004-search-read-models-and-rebuild.md`

**Description**
The search-service needs the full case (version, opened and resolved times) both from a bulk export, for the rebuild, and on every case event. `CaseChangedPayload` revision 1 carries only `caseId, bagTag, legId, state, version, reason`, and `GET /recovery-cases` is staff-only, filtered by state only and capped at 100 per page.

**Scope**
- `GET /internal/v1/export/cases?after={caseId}&size=`: keyset-paged, read-only, bounded; returns the full case including `version`, `openedAt`, `resolvedAt`, `trigger`, `assignedTo`. Service token, no gateway route.
- `CaseChangedPayload` revision 2, additive: `openedAt`, `resolvedAt`, `trigger` (`RISK`, `SCANNER_ANOMALY`, `MANUAL`), `assignedTo`; `CASE_EVENT_SCHEMA_VERSION` from 1 to 2 per `EventEnvelope`'s rules; old events read the new fields as `null`.
- Put the envelope `correlationId` into the logging context in the recovery consumers (today it is dropped, so cases opened from risk events carry a null id).
- Document in `docs/apis/RECOVERY-API.md`; contract tests for revisions 1 and 2.

**Acceptance criteria**
- [x] The export pages through more cases than one page with no gap and no repeat, and requires the service token; it writes nothing.
- [x] Every case event carries the revision 2 fields; a consumer reading revision 1 events still works.
- [x] Case events opened from risk events carry the originating `correlationId`.
- [x] Existing recovery tests (`RecoveryCaseApiTest`, `KafkaOutageTest` and the rest) still pass; `RECOVERY-API.md` updated.
