# AeroTrace – GitHub Issues Backlog

Derived from `AeroTrace_Senior_Java_Microservices_Case_Study.pdf`. Each `##` section is one issue (title = heading).
Suggested labels in `[brackets]`. Section numbers (§), BR-xx and AT-xx refer to the case study.

**Suggested build order:** #1 → #2 → #3 → #4 → #5 → #6 → #8 → #7 → #9/#10/#11 (parallel) → #12/#13 → #14–#20 (cross-cutting, start early) → #21–#22.

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
- Each agent: single responsibility, minimal tool list, explicit model, project-specific rules (service-owned schemas, `ProblemDetail`, `*.v1` events, no JPA in `common-module`, no secrets).
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
- RFC 7807 `ProblemDetail` error contract + shared `@RestControllerAdvice` base, stable error codes (e.g. `BAG_TAG_DUPLICATE`, `ILLEGAL_TRANSITION`, `CAPACITY_EXCEEDED`).
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
- Generate/propagate `X-Correlation-Id`; pass `Idempotency-Key`. Reuse the servlet `CorrelationIdFilter` and `ProblemDetails` from `common-module`.
- ProblemDetail-formatted gateway errors; OpenTelemetry tracing (W3C `traceparent` continued and propagated to downstream services) and Micrometer metrics, both exported over OTLP to an OpenTelemetry Collector that Prometheus scrapes (no admin token needed); Grafana shows a provisioned gateway dashboard (`observability/`, started by both Compose files). Downstream calls time out (2 s connect, 30 s read) instead of holding gateway threads.
- Swagger UI (springdoc) at `/swagger-ui.html` with a "Select a definition" dropdown listing every service; each service's OpenAPI document is fetched through the gateway (`/service-docs/<service>` → the service's `/v3/api-docs`). Can be switched off with `DOCS_ENABLED=false`.

**Acceptance criteria**
- [x] `API-GATEWAY` registers with Eureka and shows as `UP` (automated test against a Eureka stub; also checked against a running discovery-server).
- [x] Swagger UI at the gateway lists each service's endpoints through a dropdown (the services must expose `/v3/api-docs`).
- [x] AT-16: expired JWT → 401; wrong role → 403.
- [x] Correlation ID present on every response and downstream request.
- [x] A trace context is started or continued at the gateway and sent downstream; a slow downstream is cut off at the read timeout (both tested).
- [x] Gateway metrics and span-derived request metrics reach Prometheus through the collector, and the Grafana dashboard queries them (verified with real containers).
- [x] Rate-limit returns 429 with ProblemDetail (verified against a real Redis).
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

**Acceptance criteria**
- [ ] Tokens validate at gateway and resource services; rotation does not invalidate unexpired tokens.
- [ ] No JWT or credentials in logs.
- [ ] Authorization matrix test (role × endpoint).
- [ ] OpenAPI documents 401/403 examples.

---

## #9 Define Journey Service
`[module:journey] [priority:p0]` · Depends on #4, #8 · Spec §5.1, §7, §9

**Description**
Baggage registration, itinerary snapshots, scan ingestion API and authoritative journey state.

**Scope**
- `POST /bags` (bagTag unique → 409 `BAG_TAG_DUPLICATE`), validate IATA codes, leg chronology, minimum connection time.
- `GET /bags/{bagTag}/journey`: latest accepted state **and** immutable `scan_audit` history; passenger row-level ownership (AT-15).
- `POST /scans` with `Idempotency-Key` → `202 QUEUED`; publish `baggage.scan.received.v1`.
- **Factory Method** for versioned scan handlers per protocol; **Adapter** for barcode/RFID/conveyor payloads.
- Normalize timestamps to UTC while retaining source timezone and device timestamp; flag `LATE_EVENT` beyond grace (BR-06).
- Tables: `baggage, itinerary, itinerary_leg, scan_audit, outbox_event, processed_event`; durable dedupe on `event_id`.
- Transactional outbox + scheduled relay (at-least-once).

**Acceptance criteria**
- [ ] AT-01, AT-02, AT-03, AT-04, AT-05, AT-15.
- [ ] Two replicas run correctly (no double-publish side effects).
- [ ] Liquibase V1–V3 (see #16).
- [ ] ≥ 80% coverage of domain/application code.

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
- [ ] AT-06, AT-07, AT-08.
- [ ] `TopologyTestDriver` tests incl. grace-boundary cases.
- [ ] Metrics `stream_lag_seconds`, `baggage_risk_high`.

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

**Acceptance criteria**
- [ ] AT-09, AT-10, AT-12, AT-14, AT-22.
- [ ] Unit tests for strategies, guards, compensation.
- [ ] Redis outage does not allow duplicate cases.

---

## #12 Define Capacity Service
`[module:capacity] [priority:p0]` · Depends on #4, #8 · Spec §5.3, BR-04

**Description**
Finite recovery cart / flight / bag-slot capacity with atomic reservations.

**Scope**
- `POST /capacity/reservations`, `DELETE /capacity/reservations/{id}` (release/compensation), admin-only resource management.
- Atomic reservation in PostgreSQL (conditional `UPDATE … WHERE remaining > 0` or row lock); **DB is authoritative**, Redis only an optional cache/hint.
- Reservation TTL / provisional hold expiry job.
- Tables: `capacity_resource`, `capacity_reservation`, outbox; publish `recovery.capacity.changed.v1` (key `resourceId`).
- Metric `capacity_reservation_conflicts_total`; idempotent reservations via `Idempotency-Key`.

**Acceptance criteria**
- [ ] AT-11: 50 concurrent requests for 10 slots → exactly 10 succeed (race test).
- [ ] AT-19: Redis offline → no overbooking.
- [ ] 409 conflict contract documented in OpenAPI.

---

## #13 Define Search Service (Elasticsearch)
`[module:search] [priority:p1]` · Depends on #4, #9, #11 · Spec §5.4, §12

**Description**
Elasticsearch read models for journey and case search.

**Scope**
- Consume `recovery.case.changed.v1` (+ journey events); idempotent indexer (doc id = aggregate id, version check).
- Filters: bagTag, airport, airline, flight, risk category, case state; deterministic sort; paging + `search_after` for deep pages.
- Full backfill/reconciliation command (no duplicates, source untouched — AT-18).
- Indexing lag exposed as a metric/endpoint.
- `GET /reports/airport/{iata}?from=&to=` + hourly counts: risky bags, resolved recoveries, retries, mean resolution time.

**Acceptance criteria**
- [ ] AT-18 with Elasticsearch Testcontainer.
- [ ] Lag visible in Grafana.
- [ ] Rebuild via alias swap without downtime (preferred).

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
- Already in place from #7: OpenTelemetry Collector (`observability/otel-collector.yml`) → Prometheus → Grafana in both Compose files, with the gateway exporting traces and metrics over OTLP and a provisioned gateway dashboard. Still open here: service metrics/dashboards, Loki, Tempo (the collector has no trace store yet), alerts.
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
