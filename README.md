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

Passwords and a few endpoints come from environment variables, with no default password in the code. For local work, copy the template and adjust it:

```bash
cp .env.example .env
```

`.env` is git-ignored and never copied into Docker images. Docker Compose reads it automatically, and the Spring Boot services read it too when started from the IDE or Maven (they import `.env`, or `../.env` when started from a module folder). Real environment variables, such as IDE run-configuration values, take precedence over the file.

| Variable | Used by |
|---|---|
| `EUREKA_USER`, `EUREKA_PASSWORD` | discovery-server, and every client that registers with it |
| `REDIS_PASSWORD` | Redis, api-gateway |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | PostgreSQL |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWK_SET_URI` | api-gateway started outside Docker (inside Compose it uses `COMPOSE_JWT_ISSUER` / `COMPOSE_JWK_SET_URI`) |
