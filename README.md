# AeroTrace – Baggage Tracer

Java 25 / Spring Boot microservices for tracing airport baggage. Work items live in [docs/issues/GITHUB_ISSUES.md](docs/issues/GITHUB_ISSUES.md).

## Agents

Role-based Claude Code subagents live in [.claude/agents](.claude/agents); reusable workflows live in [.claude/skills](.claude/skills).

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

Passwords and a few endpoints come from environment variables, with no default password in the code. For local work, create a `.env` file in the repository root (one `KEY=value` per line) with the variables below.

`.env` is git-ignored and never copied into Docker images. Docker Compose reads it automatically, and the Spring Boot services read it too when started from the IDE or Maven (they import `.env`, or `../.env` when started from a module folder). Real environment variables, such as IDE run-configuration values, take precedence over the file.

In `docker-compose.yml` and `docker-compose-local.yml`, the services that need these settings (Redis, PostgreSQL, discovery-server, config-server, api-gateway) also load `.env` through `env_file` (optional: without the file the dev defaults apply). A service's explicit `environment:` entries win over `.env`, which is why the gateway container keeps the in-network identity-service URLs. Note that `env_file` passes every variable in the file to each of those containers, not only the ones the service uses.

| Variable | Used by |
|---|---|
| `EUREKA_USER`, `EUREKA_PASSWORD` | discovery-server, and every client that registers with it |
| `REDIS_PASSWORD` | Redis, api-gateway |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | PostgreSQL |
| `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | Grafana (optional, defaults `admin` / `grafana`) |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWK_SET_URI` | api-gateway started outside Docker (inside Compose it uses `COMPOSE_JWT_ISSUER` / `COMPOSE_JWK_SET_URI`) |
| `DB_PASSWORD` (also `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`; defaults `localhost`, `5433`, `aerotrace`, `aerotrace`) | identity-service started outside Docker (inside Compose it takes the `POSTGRES_*` values) |
| `IDENTITY_ISSUER` | identity-service, no default. Must equal the gateway's `JWT_ISSUER` |
| `IDENTITY_JWT_PRIVATE_KEY_LOCATION`, `IDENTITY_JWT_PUBLIC_KEY_LOCATION` (or the PEM text itself in `IDENTITY_JWT_PRIVATE_KEY`, `IDENTITY_JWT_PUBLIC_KEY`) | identity-service: the RSA key pair (PEM; the private key in PKCS#8, `BEGIN PRIVATE KEY`) that signs and verifies the access tokens. A location is a Spring resource (`file:/run/secrets/private.pem`, `classpath:...`) or a file path; inline text wins over a location. Unset, the development pair in `identity-service/src/main/resources/keys` is used, **whose private key is in this repository** |
| `IDENTITY_REFRESH_TOKEN_TTL`, `IDENTITY_PASSWORD_RESET_TTL` | identity-service: lifetime of a refresh token (default `7d`) and of a password reset token (default `15m`) |
| `DOCS_ENABLED` | api-gateway and identity-service: serve the OpenAPI definitions and Swagger UI (default `true`; they are served without a token and map every endpoint, so set `false` where that should stay private) |

## Running the stack with Docker Compose

`docker compose up -d` starts the stack. The gateway listens on port 8080 on all interfaces; put a reverse proxy with TLS in front of it before exposing it further.

Anyone can register a passenger. Staff accounts (`AGENT`, `DISPATCHER`, `ADMIN`) can only be created by an administrator through `POST /register` with an ADMIN access token, so the first administrator is made once by hand. The API docs are on by default (`DOCS_ENABLED=false` in `.env` turns them off).

Compose starts the identity-service with `LIQUIBASE_CONTEXTS=dev`, which also runs changeset `003-sample-data.sql`: five dummy accounts (`dummy.passenger`, `dummy.passenger2`, `dummy.agent`, `dummy.dispatcher`, `dummy.admin`) that all have the password `dummy-password-1`. That gives you an administrator right away. Without the `dev` context (the default outside Compose, or `LIQUIBASE_CONTEXTS=schema` in `.env`) the accounts are not created. Never run a real deployment with the `dev` context.

To create the first administrator, register a passenger, then give that user the role in the database:

```sql
INSERT INTO identity.user_role (user_id, role)
SELECT id, 'ADMIN' FROM identity.app_user WHERE username = 'your-username';
```

The identity-service listens on port 1112 (published by Compose, so its own endpoints, such as `/.well-known/jwks.json`, `/actuator/health` and `/v3/api-docs`, can be called directly). The authentication API (all under `http://localhost:8080/api/v1/auth` through the gateway, or `http://localhost:1112/api/v1/auth` directly, answers wrapped as `{"success":true,"data":...,"timestamp":...}`):

| Call | What it does |
|---|---|
| `POST /register` `{username,email,password,fullName[,role]}` | Creates an account (201). Only `PASSENGER` without a token; `AGENT`, `DISPATCHER` or `ADMIN` need an ADMIN access token |
| `POST /login` `{usernameOrEmail,password}` | Returns `accessToken`, `refreshToken`, `tokenType`, the expiry times and the roles |
| `POST /refresh-token` `{refreshToken}` | Exchanges a refresh token for a new pair. A refresh token works once; presenting a used one ends every session of that user |
| `POST /forgot-password` `{usernameOrEmail}` | Starts a password reset. The answer is the same whether or not the account exists |
| `POST /reset-password` `{resetToken,newPassword}` | Sets a new password with a reset token (once, 15 minutes) and ends every session of the user |
| `POST /logout` `{refreshToken}` | Revokes the refresh token; always succeeds |

Passwords are 12 to 72 bytes. Refresh and reset tokens are stored only as SHA-256 hashes.

Tokens are signed with the development key pair that ships inside identity-service unless you provide your own. Its private key is in this repository, so anyone can mint tokens that the platform accepts: fine for a throwaway local stack, never for anything else (identity-service logs a warning at every start while it is in use). To use your own pair:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out private.pem
openssl rsa -in private.pem -pubout -out public.pem
```

then mount both files into the identity-service container and set `IDENTITY_JWT_PRIVATE_KEY_LOCATION` and `IDENTITY_JWT_PUBLIC_KEY_LOCATION` to their paths (for example `file:/run/secrets/private.pem`).

## Postman collection

`postman/AeroTrace.postman_collection.json` and `postman/AeroTrace-local.postman_environment.json` call the authentication API through the gateway (`baseUrl` defaults to `http://localhost:8080`; folder 4 calls the identity-service directly at `identityUrl`, default `http://localhost:1112`). Import both into Postman, select the "AeroTrace - local" environment and run the collection with a delay of 150 ms or more between requests (the gateway allows about 10 requests per second per address). Folder 1 walks a passenger through register, login, refresh, forgot and reset password, login again and logout; the test scripts store the tokens in the environment. Folder 2 checks the refusals (wrong password, validation, duplicate, unknown tokens). Folder 3 needs `adminUsername` and `adminPassword` in the environment (the first admin is made by hand, see above). With newman: `newman run postman/AeroTrace.postman_collection.json -e postman/AeroTrace-local.postman_environment.json --delay-request 150 --folder "1. Passenger journey" --folder "2. Negative cases"`.

## API response format

Every service answers with the same two envelopes from `common-module`: `ApiResponse` for success (`success`, `data`, `timestamp`) and `ApiError` for failures (`code`, `message`, `status`, `path`, `validationErrors`, `timestamp`, `correlationId`). `code` is a stable value such as `VALIDATION_ERROR`, `UNAUTHENTICATED` or `BAG_TAG_DUPLICATE`; `validationErrors` names the invalid fields and the rule they broke, never the value that was sent. Responses whose format a standard fixes, such as the JWK Set at `/.well-known/jwks.json`, are not wrapped.

## Known limitations of the identity-service

- No per-account lockout or backoff and no cap on concurrent password hashing; the gateway's per-address rate limit is the only brake today.
- `forgot-password` returns the reset token in its response, because there is no mail service yet. Anyone who knows a username or email can therefore reset that account's password and take it over, and the answer reveals which accounts exist. Do not expose this service to untrusted callers until the token is delivered out of band.
- Registration answers 409 for a username or email that exists, so it reveals which accounts exist. Sessions slide: every refresh issues a new 7-day token with no absolute session limit. Creating staff accounts trusts the roles in the caller's access token (up to 15 minutes old) without a database check. The gateway rate limit is per address only, with no per-endpoint or per-account limit on `login` and `forgot-password`.
- Refresh tokens are revoked with a reason. Only a token that was rotated away more than 10 seconds ago and is presented again counts as theft and ends all sessions of that user. Expired token rows are deleted hourly once they are a day past expiry.
- Access tokens cannot be revoked (logout and password reset revoke refresh tokens only); a token stays valid until it expires (15 minutes plus a minute of clock skew).
- The audit trail's immutability is enforced by database triggers, but the service connects as the schema owner and could drop them; use a restricted runtime role where that matters.
- The signing key pair is one static pair read at start-up. There is no automatic rotation and no second key id: replacing the pair means restarting identity-service, and tokens signed with the old pair stop verifying (at most 15 minutes of tokens, plus the gateway's key cache of about a minute).
- The development key pair that ships with the service has its private key in the repository (see above).

## Observability (OpenTelemetry Collector, Tempo, Loki, Prometheus, Grafana)

Both compose files start an OpenTelemetry Collector, Tempo, Loki, Prometheus and Grafana next to the infrastructure:

```bash
docker compose -f docker-compose-local.yml up -d otel-collector tempo loki pushgateway prometheus grafana
```

| Service | Where | What it does |
|---|---|---|
| OpenTelemetry Collector | OTLP/HTTP on `localhost:4318`, OTLP/gRPC on `localhost:4317` (local compose; internal in `docker-compose.yml`) | Receives traces, metrics and logs from the services, forwards the traces to Tempo and the logs to Loki, derives request-rate, error and latency metrics from them, and exposes the metrics to Prometheus |
| Tempo | internal (`tempo:3200`, no host port) | Stores the traces; Grafana queries it (TraceQL). Traces live in the `tempo-data` volume and are dropped after 24 hours |
| Loki | internal (`loki:3100`, no host port) | Stores the service logs, each line tagged with the trace id it was written in; Grafana queries it (LogQL). Logs live in the `loki-data` volume and are dropped after 24 hours |
| Pushgateway | http://localhost:9091 (loopback only) | Holds the test-coverage numbers that `observability/push-jacoco.ps1` pushes after a build; Prometheus scrapes it |
| Prometheus | http://localhost:9090 | Scrapes the collector and the Pushgateway |
| Grafana | http://localhost:3000 (`admin` / `grafana`, see `GRAFANA_ADMIN_*`) | Prometheus, Tempo and Loki data sources and four dashboards in the AeroTrace folder: "AeroTrace - Services" (request rate, errors, latency, JVM; pick the service in the dropdown), "AeroTrace - Traces", "AeroTrace - Logs" and "AeroTrace - Coverage" (see below), provisioned from `observability/grafana`; open a trace from the table or via *Explore* > *Tempo* |

The api-gateway, the identity-service, the config-server and the discovery-server send their traces and metrics to the collector over OTLP (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`; the defaults point at `localhost:4318`), so Prometheus never has to call the gateway or present a token. Every future service gets the same settings from the shared config-repo template (`config-server/src/main/resources/config-repo/application.yml`). From a trace in Grafana you can jump to the same service's request rate and p95 latency in Prometheus. Tempo 3 logs "no jobs found" at error level while idle; that is its background compaction worker finding nothing to do, not a fault. The discovery-server's traces are mostly the Eureka protocol itself (registrations, heartbeats every 10 seconds per instance, registry fetches), so expect many small traces for it.

**"AeroTrace - Traces"** lists individual requests from Tempo, with a service dropdown (one, several or all) and a "Slower than" filter. Each row is one step (span). The first column, **Trace ID**, is a link that opens the whole request across services (the ID can also be copied into Explore > Trace ID); the others are time, service, method, route, URL, HTTP status, outcome and duration. There are five tables: requests the service handled, failed requests (HTTP 4xx/5xx or errors only), slow requests (500 ms or more, whatever the status), calls the service makes to other services, and the Eureka registry traffic (heartbeats and fetches), which is kept apart so it does not bury real requests.

**"AeroTrace - Logs"** shows the logs of each service from Loki: error / warning / line counters, log volume by level and by service, the errors and warnings, and all lines of the selected services (dropdowns for service and level, plus a text filter). Every line carries the `trace_id` of the request it was written in: click a line and use **Open trace in Tempo**, or, in a trace (*Traces* dashboard or Explore), open a span and use **Logs for this span**. The "Logs of one trace" panel lists every line of one trace across all services: paste the ID into the *Trace ID* box at the top. Logs reach the collector through Spring Boot's OTLP log export (`OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`, default `localhost:4318`) and a Logback appender declared in each service's `logback-spring.xml`; the console output is unchanged.

**"AeroTrace - Coverage"** charts the JaCoCo test coverage of every module: line, branch, instruction and method coverage (green at or above the api-gateway build gate of 90 % lines / 60 % branches), a table per module with the number of uncovered lines and the age of the report, trends over time, and the classes with the most uncovered lines. Grafana cannot read JaCoCo reports itself, so run the script, which builds and then pushes (Pushgateway and Prometheus must be running, and Docker, because the tests use Testcontainers):

```bash
powershell -ExecutionPolicy Bypass -File ./observability/push-jacoco.ps1
```

It runs `mvn verify` first and pushes nothing if the build fails. Add `-SkipBuild` to push the reports of an earlier build, or `-MavenArgs '-pl','identity-service','-am'` to build only some modules. The script reads `<module>/target/site/jacoco/jacoco.csv` and sends it to `http://localhost:9091` (override with `-PushgatewayUrl` or `PUSHGATEWAY_URL`). Pushing again replaces a module's numbers; the trend charts build up from Prometheus' history, which is lost when the Prometheus container is removed. Modules without branches show "n/a" for branch coverage.

If you add a dashboard file while Grafana is already running, restart Grafana (`docker restart grafana`) to load it.

## Running the tests

`mvn verify` runs unit tests (`*Test`) and integration tests (`*IT`). The gateway's integration tests use Testcontainers: `AbstractTestContainerConfiguration` starts a password-protected Redis in Docker and points `spring.data.redis.*` at it, so they need Docker running. They are skipped, not failed, when Docker is not available, and the JaCoCo coverage check includes them.
