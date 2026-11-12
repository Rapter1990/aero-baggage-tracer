# AeroTrace – Baggage Tracer

Java 25 / Spring Boot microservices for tracing airport baggage. Work items live in [docs/issues/GITHUB_ISSUES.md](docs/issues/GITHUB_ISSUES.md).

## Agents

Role-based Claude Code subagents live in [.claude/agents](.claude/agents); reusable workflows live in [.claude/skills](.claude/skills).

| Agent | Responsibility | Edits files |
|---|---|---|
| `principal-engineer` | Orchestrates an issue end to end, delegates to specialists | yes |
| `product-owner` | Scope, acceptance criteria, BR/AT mapping | no |
| `architect` | Boundaries, ADRs, Kafka topology | docs only |
| `api-contract-engineer` | REST, ProblemDetail, event envelopes | contracts |
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

## Observability (OpenTelemetry Collector, Tempo, Loki, Prometheus, Grafana)

Both compose files start an OpenTelemetry Collector, Tempo, Loki, Prometheus and Grafana next to the infrastructure:

```bash
docker compose -f docker-compose-local.yml up -d otel-collector tempo loki prometheus grafana
```

| Service | Where | What it does |
|---|---|---|
| OpenTelemetry Collector | OTLP/HTTP on `localhost:4318`, OTLP/gRPC on `localhost:4317` (local compose; internal in `docker-compose.yml`) | Receives traces, metrics and logs from the services, forwards the traces to Tempo and the logs to Loki, derives request-rate, error and latency metrics from them, and exposes the metrics to Prometheus |
| Tempo | internal (`tempo:3200`, no host port) | Stores the traces; Grafana queries it (TraceQL). Traces live in the `tempo-data` volume and are dropped after 24 hours |
| Loki | internal (`loki:3100`, no host port) | Stores the service logs, each line tagged with the trace id it was written in; Grafana queries it (LogQL). Logs live in the `loki-data` volume and are dropped after 24 hours |
| Prometheus | http://localhost:9090 | Scrapes the collector |
| Grafana | http://localhost:3000 (`admin` / `grafana`, see `GRAFANA_ADMIN_*`) | Prometheus, Tempo and Loki data sources and three dashboards in the AeroTrace folder: "AeroTrace - Services" (request rate, errors, latency, JVM; pick the service in the dropdown), "AeroTrace - Traces" and "AeroTrace - Logs" (see below), provisioned from `observability/grafana`; open a trace from the table or via *Explore* > *Tempo* |

The api-gateway, the config-server and the discovery-server send their traces and metrics to the collector over OTLP (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`; the defaults point at `localhost:4318`), so Prometheus never has to call the gateway or present a token. Every future service gets the same settings from the shared config-repo template (`config-server/src/main/resources/config-repo/application.yml`). From a trace in Grafana you can jump to the same service's request rate and p95 latency in Prometheus. Tempo 3 logs "no jobs found" at error level while idle; that is its background compaction worker finding nothing to do, not a fault. The discovery-server's traces are mostly the Eureka protocol itself (registrations, heartbeats every 10 seconds per instance, registry fetches), so expect many small traces for it.

**"AeroTrace - Traces"** lists individual requests from Tempo, with a service dropdown (one, several or all) and a "Slower than" filter. Each row is one step (span). The first column, **Trace ID**, is a link that opens the whole request across services (the ID can also be copied into Explore > Trace ID); the others are time, service, method, route, URL, HTTP status, outcome and duration. There are five tables: requests the service handled, failed requests (HTTP 4xx/5xx or errors only), slow requests (500 ms or more, whatever the status), calls the service makes to other services, and the Eureka registry traffic (heartbeats and fetches), which is kept apart so it does not bury real requests.

**"AeroTrace - Logs"** shows the logs of each service from Loki: error / warning / line counters, log volume by level and by service, the errors and warnings, and all lines of the selected services (dropdowns for service and level, plus a text filter). Every line carries the `trace_id` of the request it was written in: click a line and use **Open trace in Tempo**, or, in a trace (*Traces* dashboard or Explore), open a span and use **Logs for this span**. The "Logs of one trace" panel lists every line of one trace across all services: paste the ID into the *Trace ID* box at the top. Logs reach the collector through Spring Boot's OTLP log export (`OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`, default `localhost:4318`) and a Logback appender declared in each service's `logback-spring.xml`; the console output is unchanged.

If you add a dashboard file while Grafana is already running, restart Grafana (`docker restart grafana`) to load it.

## Running the tests

`mvn verify` runs unit tests (`*Test`) and integration tests (`*IT`). The gateway's integration tests use Testcontainers: `AbstractTestContainerConfiguration` starts a password-protected Redis in Docker and points `spring.data.redis.*` at it, so they need Docker running. They are skipped, not failed, when Docker is not available, and the JaCoCo coverage check includes them.
