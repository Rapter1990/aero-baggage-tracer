# AeroTrace – Baggage Tracer

Java 25 / Spring Boot microservices for tracing airport baggage.

## Contents

| Section | What it covers |
|---|---|
| [Agents](#agents) | Claude Code subagents and skills |
| [Local configuration](#local-configuration-env) | The `.env` file and all variables |
| [Docker Compose](#running-the-stack-with-docker-compose) | Start the stack, dev accounts |
| [API and flows](#api-requests-and-flows) | API documents and service addresses |
| [Response format](#api-response-format) | Success and error envelopes |
| [Rate limits](#rate-limits-and-lockout-of-the-authentication-endpoints) | Gateway limits and login lockout |
| [Known limitations](#known-limitations-of-the-identity-service) | Weak spots of identity-service |
| [Observability](#observability-opentelemetry-collector-tempo-loki-prometheus-grafana) | Traces, metrics, logs, dashboards |
| [Tests](#running-the-tests) | Run tests and chart coverage |

Work items are in [docs/issues/GITHUB_ISSUES.md](docs/issues/GITHUB_ISSUES.md).

## Agents

Claude Code subagents are in [.claude/agents](.claude/agents). Reusable workflows are in [.claude/skills](.claude/skills).

| Agent | Responsibility | Edits files |
|---|---|---|
| `principal-engineer` | Runs an issue end to end | yes |
| `product-owner` | Scope, acceptance criteria, BR/AT mapping | no |
| `architect` | Boundaries, ADRs, Kafka topology | docs only |
| `api-contract-engineer` | REST, error envelopes, event envelopes | contracts |
| `kafka-streams-engineer` | Consumers, Streams topologies, DLQ, replay | yes |
| `database-reviewer` | Liquibase, indexes, transactions | no |
| `security-reviewer` | JWT, roles, PII, secrets | no |
| `code-reviewer` | Diff review before merge | no |
| `test-engineer` | Unit, integration, concurrency, security tests | tests |
| `devops-engineer` | Docker, Kubernetes, CI/CD, observability | yes |
| `manual-qa-tester` | Black-box tests of the running system | no |

### Delegation map

| Need | Agent |
|---|---|
| Scope, acceptance criteria | `product-owner` |
| Boundaries, ADRs | `architect` |
| REST/event contracts | `api-contract-engineer` |
| Kafka consumers and Streams | `kafka-streams-engineer` |
| Persistence | `database-reviewer` |
| Auth, PII, secrets | `security-reviewer` |
| Docker, K8s, CI/CD | `devops-engineer` |
| Tests | `test-engineer` |
| Final review | `code-reviewer` |
| Running-system check | `manual-qa-tester` |

### Skills

`analyze-case`, `architecture-review`, `implement-feature`, `run`, `verify-project`, `create-issues`, `create-pr`.

#### Implementation procedure

Order in which the skills are used for one issue. `implement-feature` (step 0) is the driver: it runs the flow below on a `feature/<N>/<slug>` branch. Steps 1 to 4 can also be run on their own. `create-issues` opens the issue before and `create-pr` the pull request after.

| Step | Skill | When | What it does | Done when |
|---|---|---|---|---|
| 0 | `/implement-feature <N>` | To build one issue end to end | Reads the issue and confirms its dependencies are done; branches `feature/<N>/<slug>` from `main`; plans the smallest vertical slice and delegates with the delegation map above (`principal-engineer`); writes the failing test first (`test-engineer`), then the code, in Conventional Commits; runs `verify-project`; reviews with `code-reviewer` (and `security-reviewer` when auth or PII is touched); ticks the acceptance criteria; opens the PR with `create-pr` | Verify passes, review findings are fixed, criteria are ticked, and the PR is open (previewed with `-DryRun` first) |
| 1 | `/analyze-case <N>` | Before any code | Reads `docs/requirements/` (exact BR-xx and AT-xx wording, owning issue, tests); extracts modules, topics, roles, business rules (BR-xx), acceptance tests (AT-xx); compares them with `docs/issues/GITHUB_ISSUES.md`; `product-owner` words the result | Coverage table (requirement → issue) and proposed new issues; anything not defined in the repo is marked unverified |
| 2 | `/architecture-review` | After the design, before the build, and again on the finished diff | `architect` (boundaries), `api-contract-engineer` (contracts) and `database-reviewer` (persistence) review the modules touched; checks no JPA in `common-module`, no cross-service DB access, versioned events, idempotent consumers, correlation id propagated | Findings ranked blocker / should-fix / nit; an ADR in `docs/adr/` when a decision is needed |
| 3 | `/verify-project` | After every meaningful change, and before each push | `mvn -q spotless:check`, then `mvn -q verify` (Surefire, Failsafe, JaCoCo thresholds), then `git status` (no secrets, no `target/`) | Every step actually run and passing; failures reported with their output, never assumed green |
| 4 | `/run` | After verify passes, to see the change working | Builds (`mvn -q -DskipTests package`), starts the stack with Docker Compose, waits for `/actuator/health` and Eureka registration, smoke-tests through the gateway under `/api/v1` with a token per role | Report of which services are up, which are not, and the log lines of any that failed |

Then fix what the reviews and the run found, repeat steps 3 and 4, tick the issue's acceptance criteria, and open the pull request with `create-pr`.

## Local configuration (`.env`)

Passwords and some endpoints come from environment variables. The code has no default password. For local work, create a `.env` file in the repository root, one `KEY=value` per line.

| Where | How `.env` is used |
|---|---|
| The file | Git-ignored, never copied into images |
| Docker Compose | Reads it automatically |
| Spring Boot services | Import `.env` or `../.env` (IDE, Maven) |
| Real environment variables | Win over the file |
| `env_file` in compose files | Optional; dev defaults apply without it |
| Services using `env_file` | Redis, PostgreSQL, discovery, config, gateway |
| `environment:` entries | Win over `.env` |
| Warning | Every variable reaches each of those containers |

### Variables

Defaults marked "Compose" are for local use only.

| Variable | Used by | What it does | Default |
|---|---|---|---|
| `EUREKA_USER` | discovery-server, all clients | Registry user | Compose: `eureka` |
| `EUREKA_PASSWORD` | discovery-server, all clients | Registry password | Compose: `eureka` |
| `REDIS_PASSWORD` | Redis, api-gateway, recovery-service | Redis password (no default; set it for IDE runs too) | Compose: `redis` |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | PostgreSQL | Database account and name | Compose: `aerotrace` |
| `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | Grafana | Optional admin login | `admin` / `grafana` |
| `JWT_PUBLIC_KEY_LOCATION` or `JWT_PUBLIC_KEY` | api-gateway | Public key that verifies tokens | Dev key packaged at build |
| `JWT_AUDIENCE` | api-gateway | Expected token audience | `aerotrace-api` |
| `DB_PASSWORD` | identity, journey (outside Docker) | Database password | none, required |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` | identity, journey (outside Docker) | Database address and account | `localhost`, `5433`, `aerotrace`, `aerotrace` |
| `IDENTITY_JWT_PRIVATE_KEY_LOCATION` or `IDENTITY_JWT_PRIVATE_KEY` | identity-service | Private key that signs tokens | Dev key. **Private key is in this repository** |
| `IDENTITY_JWT_PUBLIC_KEY_LOCATION` or `IDENTITY_JWT_PUBLIC_KEY` | identity, journey | Public key that verifies tokens | Dev key |
| `IDENTITY_AUDIENCE` | identity, journey | Token audience | `aerotrace-api` |
| `IDENTITY_REFRESH_TOKEN_TTL` | identity-service | Refresh token lifetime | `7d` |
| `IDENTITY_PASSWORD_RESET_TTL` | identity-service | Reset token lifetime | `15m` |
| `IDENTITY_MAX_FAILED_LOGINS` | identity-service | Wrong passwords before lockout | `5` |
| `IDENTITY_FAILURE_WINDOW` | identity-service | Window for counting wrong passwords | `15m` |
| `IDENTITY_LOCKOUT_DURATION` | identity-service | How long a name stays locked | `15m` |
| `IDENTITY_MAX_RESET_REQUESTS` | identity-service | Reset requests per name per window | `3` |
| `IDENTITY_RESET_REQUEST_WINDOW` | identity-service | Window for reset requests | `15m` |
| `IDENTITY_MAX_CONCURRENT_HASHES` | identity-service | Password hashes running at once | `0` = one per CPU (min 2) |
| `IDENTITY_HASH_WAIT` | identity-service | Wait for a hash slot, then `429` | `2s` |
| `JOURNEY_MIN_CONNECTION_TIME` | journey-service | Shortest time between two legs | `45m` |
| `JOURNEY_LATE_EVENT_GRACE` | journey-service | Age after which a scan is late | `10m` |
| `JOURNEY_MAX_CLOCK_SKEW` | journey-service | Furthest future time a scan may have | `5m` |
| `JOURNEY_OUTBOX_RELAY_DELAY` | journey-service | Pause between relay runs | `200ms` |
| `JOURNEY_OUTBOX_BATCH_SIZE` | journey-service | Events sent per relay run | `200` |
| `JOURNEY_OUTBOX_RETENTION` | journey-service | How long published events are kept | `7d` |
| `CAPACITY_SERVICE_URL` | recovery-service | Where the capacity-service answers | `http://localhost:1116` |
| `CAPACITY_READ_TIMEOUT` | recovery-service | A slower capacity-service counts as unavailable (503) | `5s` |
| `CAPACITY_HOLD_TIMEOUT` | recovery-service | A case RESERVED longer than this is cancelled and its hold released | `15m` |
| `RECOVERY_POLICY_CACHE_TTL` | recovery-service | How long Redis may serve the cached recovery policy | `5m` |
| `RECOVERY_DEDUPE_RETENTION` | recovery-service | How long processed event ids are remembered (keep above the topics' retention) | `14d` |
| `REDIS_TIMEOUT` | recovery-service | Redis command timeout; after it the database answers | `500ms` |
| `KAFKA_BOOTSTRAP_SERVERS` | journey, risk-streams | Kafka address | `localhost:9092` (Compose: `kafka:9092`) |
| `TRUSTED_PROXIES` | api-gateway | Proxies whose `X-Forwarded-For` is trusted | empty = header ignored |
| `AUTH_RATE_LIMIT_REPLENISH_RATE` | api-gateway | Auth tokens refilled per second | `0.2` (12 a minute) |
| `AUTH_RATE_LIMIT_BURST_CAPACITY` | api-gateway | Auth burst size | `10` |
| `DOCS_ENABLED` | gateway, identity, journey | Serve OpenAPI and Swagger UI | `true` |

Notes on keys:

| Topic | Rule |
|---|---|
| Format | PEM. Private key is PKCS#8 (`BEGIN PRIVATE KEY`) |
| Location | Spring resource (`file:...`) or file path |
| Inline text | Wins over a location |
| `DOCS_ENABLED` | Docs need no token; `false` hides them |
| `TRUSTED_PROXIES` | Behind a proxy, else one shared bucket |

## Running the stack with Docker Compose

```bash
docker compose up -d
```

| Topic | What to know |
|---|---|
| Gateway | Port 8080, all interfaces; add a TLS proxy |
| Passengers | Anyone can register |
| Staff accounts | Only an ADMIN token can create them |
| API docs | On by default. `DOCS_ENABLED=false` turns them off |
| Kafka UI | Port 8090, cluster `local` |

### Dev accounts

Compose starts identity-service with `LIQUIBASE_CONTEXTS=dev`. Changeset `003-sample-data.sql` then creates five accounts. All use the password `dummy-password-1`.

| Account | Role |
|---|---|
| `dummy.passenger` | PASSENGER |
| `dummy.passenger2` | PASSENGER |
| `dummy.agent` | AGENT |
| `dummy.dispatcher` | DISPATCHER |
| `dummy.admin` | ADMIN |

| `LIQUIBASE_CONTEXTS` | Dummy accounts | Default where |
|---|---|---|
| `dev` | created | Docker Compose |
| `schema` | not created | outside Compose |

**Never run a real deployment with the `dev` context.**

### First administrator without the dev context

Register a passenger, then give that user the role in the database:

```sql
INSERT INTO identity.user_role (user_id, role)
SELECT id, 'ADMIN' FROM identity.app_user WHERE username = 'your-username';
```

## API requests and flows

| Document | What it has |
|---|---|
| [docs/apis/API.md](docs/apis/API.md) | Overview and the Postman collection |
| [docs/apis/IDENTITY-API.md](docs/apis/IDENTITY-API.md) | Identity calls, tokens, flows, requests |
| [docs/apis/JOURNEY-API.md](docs/apis/JOURNEY-API.md) | Journey calls, scans, events, flows, requests |

| Service | Address |
|---|---|
| api-gateway (clients call this) | `http://localhost:8080` |
| identity-service (direct) | `http://localhost:1112` |
| journey-service (direct) | `http://localhost:1113` |
| risk-streams-service (actuator only, no public API) | `http://localhost:1114` |
| recovery-service (direct) | `http://localhost:1115` |

In Compose, the one-shot `kafka-init` job creates the Kafka topics before journey-service, risk-streams-service and recovery-service start (idempotent, `--if-not-exists`):

| Topic | Partitions | Retention |
|---|---|---|
| `baggage.scan.received.v1` | 6 | 7d |
| `baggage.risk.changed.v1` | 6 | 7d |
| `baggage.scan.anomaly.v1` | 6 | 7d |
| `recovery.case.changed.v1` | 6 | 7d |
| `recovery.capacity.changed.v1` | 6 | 7d |
| `baggage.dead-letter.v1` | 3 | 30d |

All use `cleanup.policy=delete` and replication factor 1. A topic that already exists (for example auto-created earlier with 1 partition) is not changed: delete it once in Kafka UI to get these settings. `docker-compose-local.yml` has the same `kafka-init` job, so the topics exist with these settings when you run the services from the IDE.

Compose publishes the direct ports. Each service has `/actuator/health` and `/v3/api-docs`. The Postman collection `AeroTrace` is in `postman/`, see [Postman collection](docs/apis/API.md#postman-collection).

## API response format

| Envelope | Used for | Fields |
|---|---|---|
| `ApiResponse` | success | `success`, `data`, `timestamp` |
| `ApiError` | failure | `code`, `message`, `status`, `path`, `validationErrors`, `timestamp`, `correlationId` |

| Field | Rule |
|---|---|
| `code` | Stable value, e.g. `VALIDATION_ERROR` |
| `validationErrors` | Field and broken rule, never the value |
| Standard formats | Not wrapped |

## Rate limits and lockout of the authentication endpoints

Two places slow down guessing, flooding and costly password hashes.

### api-gateway

Needs Redis. All gateway instances share the buckets.

| Topic | What happens |
|---|---|
| Bucket | One per auth endpoint and client address |
| Default | 12 calls a minute, burst of 10 |
| Refused call | `429` with `ApiError` and `Retry-After` |
| Client address | The connecting peer |
| Behind a TLS proxy | Set `TRUSTED_PROXIES` |
| `X-Forwarded-For` | Read only from trusted proxies |
| Forged headers | Ignored, so no fresh bucket |

### identity-service

Uses PostgreSQL. Instances share the counters, which survive restarts.

| Protection | What happens |
|---|---|
| Lockout trigger | 5 wrong passwords in 15 minutes |
| Lockout length | 15 minutes, fixed, never extended |
| While locked | `login` answers `429` with `Retry-After` |
| While locked | Even the right password is refused |
| While locked | Unknown names get the same answer |
| The failing attempt | Still gets the usual `401` |
| Successful sign-in | Clears the count |
| Reset requests | 3 per name per 15 minutes |
| Over the limit | `429` for 15 minutes, same for unknown names |
| Password hashing | One BCrypt hash per CPU core (min 2) |
| No hash slot in 2 s | `429` with `Retry-After: 1` |
| Stored counters | SHA-256 hash of the name, never the text |
| Audit trail | Lock written once (`LOGIN_LOCKED`, `PASSWORD_RESET_LOCKED`) |
| Metrics | Counted by reason, never by name |
| Cleanup | Idle counters deleted hourly after a day |

### How many requests are allowed?

| Limit | Allowed |
|---|---|
| General API | **10 per second** (burst 20) |
| One auth endpoint, e.g. `login` | **12 per minute** (burst 10) |

### How long must I wait for the next one?

| You sent at once | General API | `login` |
|---|---|---|
| 10 requests | no wait | wait **5 seconds** |
| 20 requests | wait **1 second** | first 10 allowed, wait **5 seconds** |

A request that is too early gets a `429`. Its `Retry-After` header gives the seconds to wait. Change the limits with the `IDENTITY_*` and `AUTH_RATE_LIMIT_*` [variables](#variables).

## Known limitations of the identity-service

| Area | Limitation |
|---|---|
| Lockout | Per typed name, fixed length |
| Lockout | Repeated guessing keeps a victim locked |
| Lockout | Unknown names can be locked too |
| Lockout | Password reset works while locked |
| Password reset | `forgot-password` returns the reset token (no mail yet) |
| Password reset | Anyone knowing a username can take over |
| Password reset | **Keep it away from untrusted callers** |
| Registration | `409` reveals which accounts exist |
| Sessions | Sliding: each refresh issues 7 more days |
| Sessions | No absolute session limit |
| Staff accounts | Roles come from token (≤15 min old) |
| Refresh tokens | Reuse after 10 seconds ends all sessions |
| Refresh tokens | Expired rows deleted hourly after a day |
| Access tokens | Cannot be revoked before they expire |
| Access tokens | Valid 15 minutes plus a minute of skew |
| Audit trail | Triggers keep it immutable; owner can drop them |
| Audit trail | Use a restricted runtime role where it matters |
| Signing keys | One static pair, read at start-up |
| Signing keys | No JWKS, no `kid`, no rotation |
| Signing keys | Replacing the pair needs restarts, old tokens fail |
| Signing keys | Gateway and journey-service hold the public key |
| Development key | Its private key is in this repository |

## Observability (OpenTelemetry Collector, Tempo, Loki, Prometheus, Grafana)

Both compose files start these tools:

```bash
docker compose -f docker-compose-local.yml up -d otel-collector tempo loki pushgateway prometheus grafana
```

Kafka UI starts with Kafka: `docker compose -f docker-compose-local.yml up -d kafka kafka-ui`.

| Tool | Where | What it does |
|---|---|---|
| OpenTelemetry Collector | `localhost:4318` (HTTP), `localhost:4317` (gRPC) | Routes traces, metrics, logs to the stores |
| Tempo | internal `tempo:3200` | Traces, kept 24 hours |
| Loki | internal `loki:3100` | Logs with trace ids, kept 24 hours |
| Pushgateway | http://localhost:9091 | Holds coverage numbers for Prometheus |
| Prometheus | http://localhost:9090 | Scrapes collector and Pushgateway |
| Grafana | http://localhost:3000 (`admin` / `grafana`) | Dashboards for all three stores |
| Kafka UI | http://localhost:8090 | Browse Kafka topics and messages |

### How the services connect

| Topic | What to know |
|---|---|
| Senders | Gateway, identity, journey, config, discovery |
| Protocol | OTLP to the collector, default `localhost:4318` |
| Settings | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`, `OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` |
| Prometheus | Needs no token, calls no service |
| New services | Get settings from the shared config-repo template |
| Trace in Grafana | Links to request rate and p95 latency |
| Tempo 3 "no jobs found" | Harmless idle message |
| discovery-server traces | Mostly Eureka heartbeats, many and small |

### Dashboards

They are in the AeroTrace folder, provisioned from `observability/grafana`.

| Dashboard | What it shows |
|---|---|
| **AeroTrace - Services** | Request rate, errors, latency, JVM |
| **AeroTrace - Traces** | Single requests, one row per span |
| **AeroTrace - Logs** | Service logs from Loki |
| **AeroTrace - Coverage** | JaCoCo coverage of every module |
| **AeroTrace - Kafka Topics** | Messages, errors, send time per topic |
| **AeroTrace - Kafka Streams** | Streams state, HIGH-risk bags, lag, risk/anomaly/dead-letter events, state stores |
| **AeroTrace - Outbox** | Outbox backlog, delay, failures, relay runs |

| Dashboard | How to use it |
|---|---|
| Services | Pick the service in the dropdown |
| Traces | Filter by service and "Slower than" |
| Traces | Click **Trace ID** to open the whole request |
| Logs | Filter by service, level, text |
| Logs | **Open trace in Tempo** from any line |
| Logs | **Logs for this span** from a trace |
| Logs | "Logs of one trace": paste a *Trace ID* |
| Coverage | Green = gateway gate (90 % lines) |
| Coverage | Branch gate is 60 % |
| Coverage | Lists classes below the 90 % line gate |
| Kafka Topics | Pick topics, open a send trace |
| Kafka Topics | Link opens Kafka UI for messages |
| Kafka Streams | Pick the application; Running, lag and processing errors should stay green |
| Kafka Streams | Dead letters or processing errors above 0 need a look |
| Kafka Streams | Link opens the consumer group in Kafka UI |
| Outbox | Waiting and oldest should stay near 0 |
| Outbox | Parked above 0 needs a person |
| Outbox | Open a relay run to see its sends |

Tables of the Traces dashboard:

| Table | Content |
|---|---|
| Requests | Requests the service handled |
| Failed | HTTP 4xx/5xx or errors |
| Slow | 500 ms or more |
| Calls to other services | Outgoing calls |
| Eureka | Heartbeats and fetches, kept apart |

Logs reach the collector through Spring Boot's OTLP export and a Logback appender in each `logback-spring.xml`. The console output is unchanged.

### Coverage script

Grafana cannot read JaCoCo reports. Run this from the project root (PowerShell or the IntelliJ terminal). It builds, then pushes the numbers:

```bash
powershell -ExecutionPolicy Bypass -File .\observability\push-jacoco.ps1
```

| Topic | What to know |
|---|---|
| Needs | Pushgateway, Prometheus and Docker running |
| Build | Runs `mvn verify`, pushes nothing if it fails |
| `-SkipBuild` | Pushes reports of an earlier build |
| `-MavenArgs '-pl','identity-service','-am'` | Builds only some modules |
| Source | `<module>/target/site/jacoco/jacoco.csv` |
| Target | `http://localhost:9091` (`-PushgatewayUrl` or `PUSHGATEWAY_URL`) |
| Pushing again | Replaces that module's numbers |
| Trend charts | Use Prometheus history, lost if container removed |
| No branches | Shows "n/a" for branch coverage |

If you add a dashboard file while Grafana runs, restart it: `docker restart grafana`.

## Running the tests

Run the tests and chart their coverage in one step, from the project root:

```bash
powershell -ExecutionPolicy Bypass -File .\observability\push-jacoco.ps1
```

The script runs `mvn verify` and pushes numbers only if it succeeds. `mvn verify` runs unit tests (`*Test`) and integration tests (`*IT`).

| Topic | What to know |
|---|---|
| Docker | Integration tests use Testcontainers |
| api-gateway | Starts a password-protected Redis |
| identity, journey | Start a PostgreSQL container |
| journey-service | Also starts Kafka for the relay |
| recovery-service | Starts PostgreSQL, a password-protected Redis and Kafka, and a stand-in capacity-service on a real port; one class points Redis at a dead port |
| No Docker | Tests are skipped, not failed |
| No Docker with `CI` set | Identity and journey fail the build |
| Coverage check | Includes integration tests |
