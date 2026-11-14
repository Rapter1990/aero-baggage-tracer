# AeroTrace – Baggage Tracer

Java 25 / Spring Boot microservices for tracing airport baggage.

**Contents:** [Agents](#agents) · [Local configuration](#local-configuration-env) · [Docker Compose](#running-the-stack-with-docker-compose) · [API and flows](#api-requests-and-flows) · [Response format](#api-response-format) · [Rate limits](#rate-limits-and-lockout-of-the-authentication-endpoints) · [Known limitations](#known-limitations-of-the-identity-service) · [Observability](#observability-opentelemetry-collector-tempo-loki-prometheus-grafana) · [Tests](#running-the-tests)

Work items are in [docs/issues/GITHUB_ISSUES.md](docs/issues/GITHUB_ISSUES.md).

## Agents

Claude Code subagents are in [.claude/agents](.claude/agents). Reusable workflows are in [.claude/skills](.claude/skills).

| Agent | Responsibility | Edits files |
|---|---|---|
| `principal-engineer` | Orchestrates an issue end to end, delegates to specialists | yes |
| `product-owner` | Scope, acceptance criteria, BR/AT mapping | no |
| `architect` | Boundaries, ADRs, Kafka topology | docs only |
| `api-contract-engineer` | REST, ApiError and ApiResponse, event envelopes | contracts |
| `kafka-streams-engineer` | Consumers, Streams topologies, DLQ, replay | yes |
| `database-reviewer` | Liquibase, indexes, transactions | no |
| `security-reviewer` | JWT, roles, PII, secrets | no |
| `code-reviewer` | Diff review before merge | no |
| `test-engineer` | Unit, integration, concurrency, security matrix | tests |
| `devops-engineer` | Docker, Kubernetes, CI/CD, observability | yes |
| `manual-qa-tester` | Black-box testing of the running system | no |

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

`analyze-case`, `architecture-review`, `implement-feature`, `run`, `verify-project`.

## Local configuration (`.env`)

Passwords and a few endpoints come from environment variables. The code has no default password.

For local work, create a `.env` file in the repository root. Write one `KEY=value` per line.

| Where | How `.env` is used |
|---|---|
| The file | Git-ignored. Never copied into Docker images |
| Docker Compose | Reads it automatically |
| Spring Boot services | Read it when started from the IDE or Maven. They import `.env`, or `../.env` from a module folder |
| Real environment variables | Win over the file (for example IDE run-configuration values) |
| `env_file` in both compose files | Redis, PostgreSQL, discovery-server, config-server and api-gateway load it. The file is optional: without it the dev defaults apply |
| `environment:` entries of a service | Win over `.env`. That is why the gateway container keeps the in-network identity-service URLs |
| Warning | `env_file` passes **every** variable in the file to each of those containers, not only the ones the service uses |

### Variables

| Variable | Used by | What it does |
|---|---|---|
| `EUREKA_USER`, `EUREKA_PASSWORD` | discovery-server and every client | Login of the service registry |
| `REDIS_PASSWORD` | Redis, api-gateway | Redis password |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | PostgreSQL | Database account and name |
| `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | Grafana | Optional. Defaults: `admin` / `grafana` |
| `JWT_PUBLIC_KEY_LOCATION` (or PEM text in `JWT_PUBLIC_KEY`), `JWT_AUDIENCE` | api-gateway | Public key that verifies tokens, and the expected audience. Default key: a copy of identity-service's `keys/public.pem`, packaged at build time (the development key). The token issuer is fixed in code |
| `DB_PASSWORD`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` | identity-service, journey-service (outside Docker) | Database access. Defaults: `localhost`, `5433`, `aerotrace`, `aerotrace`. `DB_PASSWORD` has no default. In Compose they use the `POSTGRES_*` values |
| `IDENTITY_JWT_PRIVATE_KEY_LOCATION`, `IDENTITY_JWT_PUBLIC_KEY_LOCATION` (or PEM text in `IDENTITY_JWT_PRIVATE_KEY`, `IDENTITY_JWT_PUBLIC_KEY`) | identity-service. journey-service uses the public key only | RSA key pair (PEM) that signs and verifies tokens. The private key is PKCS#8 (`BEGIN PRIVATE KEY`). A location is a Spring resource (`file:/run/secrets/private.pem`, `classpath:...`) or a file path. Inline text wins over a location. Default: the development pair in `identity-service/src/main/resources/keys`. **Its private key is in this repository** |
| `IDENTITY_AUDIENCE` | identity-service, journey-service | Token audience. Default `aerotrace-api` |
| `IDENTITY_REFRESH_TOKEN_TTL`, `IDENTITY_PASSWORD_RESET_TTL` | identity-service | Lifetime of a refresh token (default `7d`) and of a reset token (default `15m`) |
| `IDENTITY_MAX_FAILED_LOGINS`, `IDENTITY_FAILURE_WINDOW`, `IDENTITY_LOCKOUT_DURATION` | identity-service | A typed name with this many wrong passwords (default `5`) within the window (default `15m`) is locked for the lockout time (default `15m`). See [Rate limits](#rate-limits-and-lockout-of-the-authentication-endpoints) |
| `IDENTITY_MAX_RESET_REQUESTS`, `IDENTITY_RESET_REQUEST_WINDOW` | identity-service | Password reset requests answered per typed name (default `3`) within the window (default `15m`) |
| `IDENTITY_MAX_CONCURRENT_HASHES`, `IDENTITY_HASH_WAIT` | identity-service | Password hashes that may run at once (default `0` = one per CPU core, at least 2), and how long a call waits for a slot before a `429` (default `2s`) |
| `JOURNEY_MIN_CONNECTION_TIME` | journey-service | Shortest time between two legs. Default `45m` |
| `JOURNEY_LATE_EVENT_GRACE`, `JOURNEY_MAX_CLOCK_SKEW` | journey-service | How old a scan may be on arrival before it is flagged late (default `10m`). How far in the future a scan time may be (default `5m`) |
| `JOURNEY_OUTBOX_RELAY_DELAY`, `JOURNEY_OUTBOX_BATCH_SIZE`, `JOURNEY_OUTBOX_RETENTION` | journey-service | Outbox relay: pause between runs (default `200ms`), events per run (default `200`), how long published events are kept (default `7d`) |
| `KAFKA_BOOTSTRAP_SERVERS` | journey-service | Kafka address. Default `localhost:9092` (`kafka:9092` in Compose) |
| `TRUSTED_PROXIES` | api-gateway | Reverse proxies (comma-separated addresses or CIDR networks, for example `10.0.0.0/8`) whose `X-Forwarded-For` is trusted for rate limiting. Empty (default): the header is ignored. Behind a proxy, set it, or all clients share the proxy's bucket |
| `AUTH_RATE_LIMIT_REPLENISH_RATE`, `AUTH_RATE_LIMIT_BURST_CAPACITY` | api-gateway | Stricter bucket of each `/api/v1/auth/*` endpoint per client address. Defaults: `0.2` a second (12 a minute) and a burst of `10` |
| `DOCS_ENABLED` | api-gateway, identity-service, journey-service | Serve the OpenAPI definitions and Swagger UI. Default `true`. They need no token and map every endpoint, so set `false` where that should stay private |

## Running the stack with Docker Compose

```bash
docker compose up -d
```

| Topic | What to know |
|---|---|
| Gateway | Listens on port 8080 on all interfaces. Put a reverse proxy with TLS in front of it before you expose it |
| Passengers | Anyone can register a passenger |
| Staff accounts | `AGENT`, `DISPATCHER` and `ADMIN` can only be created by an administrator: `POST /register` with an ADMIN access token |
| API docs | On by default. Set `DOCS_ENABLED=false` in `.env` to turn them off |

### Dev accounts

Compose starts identity-service with `LIQUIBASE_CONTEXTS=dev`. This runs changeset `003-sample-data.sql`, which creates five dummy accounts. All use the password `dummy-password-1`.

| Account | Role |
|---|---|
| `dummy.passenger` | PASSENGER |
| `dummy.passenger2` | PASSENGER |
| `dummy.agent` | AGENT |
| `dummy.dispatcher` | DISPATCHER |
| `dummy.admin` | ADMIN |

| `LIQUIBASE_CONTEXTS` | Dummy accounts | Where it is the default |
|---|---|---|
| `dev` | created (you get an administrator right away) | Docker Compose |
| `schema` | not created | outside Compose. Or set it in `.env` |

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
| [docs/API.md](docs/API.md) | Overview, the Postman collection and how to run it |
| [docs/IDENTITY-API.md](docs/IDENTITY-API.md) | Identity Service: requests, tokens and keys, flows, every Postman request |
| [docs/JOURNEY-API.md](docs/JOURNEY-API.md) | Journey Service: requests, scans, events, flows, every Postman request |

| What | Address |
|---|---|
| api-gateway (clients call this) | `http://localhost:8080` |
| identity-service (direct) | `http://localhost:1112` |
| journey-service (direct) | `http://localhost:1113` |

Compose publishes the direct ports, so `/actuator/health` and `/v3/api-docs` of each service can be called directly.

The Postman collection `AeroTrace` is in `postman/`. It has one folder per service. See [Postman collection](docs/API.md#postman-collection).

## API response format

Every service answers with the same two envelopes from `common-module`.

| Envelope | Used for | Fields |
|---|---|---|
| `ApiResponse` | success | `success`, `data`, `timestamp` |
| `ApiError` | failure | `code`, `message`, `status`, `path`, `validationErrors`, `timestamp`, `correlationId` |

| Field | Rule |
|---|---|
| `code` | A stable value, such as `VALIDATION_ERROR`, `UNAUTHENTICATED` or `BAG_TAG_DUPLICATE` |
| `validationErrors` | Names the invalid field and the rule it broke. Never the value that was sent |
| Standard formats | Responses whose format is fixed by a standard are not wrapped |

## Rate limits and lockout of the authentication endpoints

Guessing passwords, flooding registrations and burning CPU on password hashes are slowed down in two places. Each place does what it can see.

### api-gateway

Needs Redis. All gateway instances share the buckets.

| Topic | What happens |
|---|---|
| Bucket | Each `/api/v1/auth/*` endpoint has its own bucket per client address. It is stricter than the general limit: 12 calls a minute, burst of 10 (`AUTH_RATE_LIMIT_*`) |
| Refused call | A `429` with the shared `ApiError` and a `Retry-After` header |
| Client address | The connecting peer |
| Behind a TLS proxy | Set `TRUSTED_PROXIES`. The gateway reads `X-Forwarded-For` from the right, skips trusted proxies, and uses the first address that is not trusted |
| Forged headers | Anything a client puts in front of that is ignored. With no trusted proxy the header is never read, so it cannot be forged to get a fresh bucket |

### identity-service

Uses PostgreSQL. All instances share the counters, and they survive restarts.

| Protection | What happens |
|---|---|
| **Lockout** | A typed name (username or email, lower case, known or not) with 5 wrong passwords within 15 minutes is locked for 15 minutes |
| While locked | `login` answers `429` with `Retry-After`, even for the right password. It does not look up the account or hash anything, and answers the same for names that do not exist |
| The failing attempt | The failure that triggers the lock still gets the usual `401` |
| End of the lock | A successful sign-in clears the count. The lock has a fixed end: more attempts do not extend it |
| **Password reset requests** | 3 per typed name per 15 minutes are answered. The next one, and everything after it for 15 minutes, is a `429`. Unknown names get the same answer |
| **Bounded hashing** | At most one BCrypt hash per CPU core (at least 2) runs at once. A call that cannot get a slot within 2 seconds gets a `429` with `Retry-After: 1`. It does not queue |
| **Privacy** | The counters store only a SHA-256 hash of the typed name, never the text |
| Audit trail | The lock is written once (`LOGIN_LOCKED`, `PASSWORD_RESET_LOCKED`), not for every refused attempt |
| Metrics | Refused calls are counted by reason (`identity.auth.refused{reason=locked\|throttled\|busy}`), never by name |
| Cleanup | Counters that stopped changing a day ago are deleted hourly |

### How many requests are allowed?

| Limit | Allowed |
|---|---|
| General API | **10 requests per second** (up to 20 at once) |
| One authentication endpoint, for example `login` | **12 requests per minute**, that is 1 request per 5 seconds (up to 10 at once) |

### How long must I wait for the next one?

| You sent at once | General API | `login` |
|---|---|---|
| 10 requests | no wait | wait **5 seconds** |
| 20 requests | wait **1 second** | only the first 10 are allowed, wait **5 seconds** |

A request that is too early gets a `429`. Its `Retry-After` header says how many seconds to wait.

Change the limits with the `IDENTITY_*` and `AUTH_RATE_LIMIT_*` variables in the [variables table](#variables).

## Known limitations of the identity-service

| Area | Limitation |
|---|---|
| Lockout | It is per typed name and fixed in length. Someone who keeps guessing a victim's name locks it for 15 minutes again and again. They cannot extend a running lock, and the gateway's per-address bucket limits how often they can try |
| Lockout | The counters are per typed name, so an attacker can also lock a name that does not exist |
| Lockout | Resetting the password with a reset token still works while a name is locked |
| Password reset | `forgot-password` returns the reset token in its response, because there is no mail service yet. Anyone who knows a username or email can reset that password and take over the account. The answer also reveals which accounts exist. **Do not expose this service to untrusted callers until the token is delivered out of band** |
| Registration | It answers `409` for a username or email that exists. This reveals which accounts exist |
| Sessions | They slide. Every refresh issues a new 7-day token. There is no absolute session limit |
| Staff accounts | Creating them trusts the roles in the caller's access token (up to 15 minutes old). There is no database check |
| Refresh tokens | They are revoked with a reason. A token that was rotated away more than 10 seconds ago and is presented again counts as theft and ends all sessions of that user. Expired token rows are deleted hourly once they are a day past expiry |
| Access tokens | They cannot be revoked. Logout and password reset revoke refresh tokens only. A token stays valid until it expires (15 minutes plus a minute of clock skew) |
| Audit trail | Database triggers keep it immutable. The service connects as the schema owner and could drop them. Use a restricted runtime role where that matters |
| Signing keys | One static pair, read at start-up. The gateway and the journey-service hold a copy of the public key. There is no key-set endpoint, no `kid` and no rotation. To replace the pair, restart identity-service, the gateway and the journey-service. Tokens signed with the old pair stop verifying |
| Development key | Its private key is in the repository (see [Variables](#variables)) |

## Observability (OpenTelemetry Collector, Tempo, Loki, Prometheus, Grafana)

Both compose files start these tools next to the infrastructure:

```bash
docker compose -f docker-compose-local.yml up -d otel-collector tempo loki pushgateway prometheus grafana
```

| Tool | Where | What it does |
|---|---|---|
| OpenTelemetry Collector | OTLP/HTTP `localhost:4318`, OTLP/gRPC `localhost:4317` (local compose; internal in `docker-compose.yml`) | Receives traces, metrics and logs from the services. Sends traces to Tempo and logs to Loki. Derives request-rate, error and latency metrics. Exposes the metrics to Prometheus |
| Tempo | internal (`tempo:3200`) | Stores traces for 24 hours in the `tempo-data` volume. Grafana queries it (TraceQL) |
| Loki | internal (`loki:3100`) | Stores logs for 24 hours in the `loki-data` volume. Each line has the id of its trace. Grafana queries it (LogQL) |
| Pushgateway | http://localhost:9091 | Holds the coverage numbers that `observability/push-jacoco.ps1` pushes. Prometheus scrapes it |
| Prometheus | http://localhost:9090 | Scrapes the collector and the Pushgateway |
| Grafana | http://localhost:3000 (`admin` / `grafana`, see `GRAFANA_ADMIN_*`) | Data sources for Prometheus, Tempo and Loki, and four dashboards in the AeroTrace folder (below). Provisioned from `observability/grafana` |

### How the services connect

| Topic | What to know |
|---|---|
| Who sends | The api-gateway, identity-service, journey-service, config-server and discovery-server send traces and metrics to the collector over OTLP (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`, default `localhost:4318`) |
| Prometheus | It never has to call a service or present a token |
| New services | They get the same settings from the shared template `config-server/src/main/resources/config-repo/application.yml` |
| In Grafana | A trace links to the request rate and p95 latency of the same service |
| Tempo 3 | It logs "no jobs found" at error level while idle. This is its compaction worker finding nothing to do. It is not a fault |
| discovery-server traces | Most are the Eureka protocol itself (registrations, heartbeats every 10 seconds per instance, registry fetches). Expect many small traces |

### Dashboards

| Dashboard | What it shows | How to use it |
|---|---|---|
| **AeroTrace - Services** | Request rate, errors, latency and JVM | Pick the service in the dropdown |
| **AeroTrace - Traces** | Individual requests from Tempo. Each row is one step (span). Columns: Trace ID, time, service, method, route, URL, HTTP status, outcome, duration | Filter by service (one, several or all) and "Slower than". Click the **Trace ID** link to open the whole request across services. You can also copy it into *Explore* > *Trace ID* |
| **AeroTrace - Logs** | Logs of each service from Loki: error, warning and line counters, volume by level and by service, errors and warnings, all lines of the selected services | Filter by service, level and text. Every line has a `trace_id`. Click a line and use **Open trace in Tempo**. In a trace, open a span and use **Logs for this span** |
| **AeroTrace - Coverage** | JaCoCo test coverage of every module: line, branch, instruction and method coverage, a table per module, trends over time, and classes below the 90 % line gate | Green means at or above the api-gateway build gate (90 % lines, 60 % branches). Push the numbers with the script below |

The Traces dashboard has five tables:

| Table | Content |
|---|---|
| Requests | Requests the service handled |
| Failed | HTTP 4xx/5xx or errors only |
| Slow | 500 ms or more, whatever the status |
| Calls to other services | Calls the service makes |
| Eureka | Registry traffic (heartbeats and fetches), kept apart so it does not bury real requests |

The Logs dashboard also has "Logs of one trace": it lists every line of one trace across all services. Paste the id into the *Trace ID* box at the top. Logs reach the collector through Spring Boot's OTLP log export (`OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`, default `localhost:4318`) and a Logback appender in each service's `logback-spring.xml`. The console output is unchanged.

### Coverage script

Grafana cannot read JaCoCo reports. Run this script from the project root (PowerShell or the IntelliJ terminal). It builds, then pushes the numbers:

```bash
powershell -ExecutionPolicy Bypass -File .\observability\push-jacoco.ps1
```

| Topic | What to know |
|---|---|
| Needs | Pushgateway and Prometheus running (the Compose stack is enough). Docker running, because the tests use Testcontainers |
| Build | It runs `mvn verify` first and pushes nothing if the build fails |
| `-SkipBuild` | Pushes the reports of an earlier build |
| `-MavenArgs '-pl','identity-service','-am'` | Builds only some modules |
| Source and target | It reads `<module>/target/site/jacoco/jacoco.csv` and sends it to `http://localhost:9091`. Override with `-PushgatewayUrl` or `PUSHGATEWAY_URL` |
| Pushing again | Replaces a module's numbers. The trend charts build from Prometheus history, which is lost when the Prometheus container is removed |
| Branch coverage | Modules without branches show "n/a" |

If you add a dashboard file while Grafana is running, restart it to load the file: `docker restart grafana`.

## Running the tests

To run the tests and chart their coverage in Grafana in one step, run this from the project root (PowerShell or the IntelliJ terminal):

```bash
powershell -ExecutionPolicy Bypass -File .\observability\push-jacoco.ps1
```

The script runs `mvn verify` itself. It pushes the JaCoCo numbers to the Pushgateway only if the build succeeds. See the coverage dashboard under [Observability](#observability-opentelemetry-collector-tempo-loki-prometheus-grafana).

`mvn verify` runs unit tests (`*Test`) and integration tests (`*IT`).

| Topic | What to know |
|---|---|
| Docker | The integration tests use Testcontainers, so they need Docker running |
| api-gateway | `AbstractTestContainerConfiguration` starts a password-protected Redis and points `spring.data.redis.*` at it |
| identity-service, journey-service | A PostgreSQL container. The journey-service also uses a Kafka container for the outbox relay |
| No Docker | The tests are skipped, not failed. In identity-service and journey-service, a missing Docker fails the build when the `CI` variable is set |
| Coverage | The JaCoCo coverage check includes the integration tests |
