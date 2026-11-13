## What this PR delivers (final state)

The commit list below is the history of the branch. Several early commits (`POST /auth/token`, seed users, key rotation, database-held keys) were replaced later; this is what the branch contains now.

- **identity-service** (servlet, Spring Boot 4, JPA, Liquibase schema `identity`) listening on **port 1112** (published by `docker-compose.yml`; the gateway reaches it through Eureka) with `AuthController`: `register`, `login`, `refresh-token`, `forgot-password`, `reset-password`, `logout` under `/api/v1/auth`, plus the JWKS at `/.well-known/jwks.json`.
- **Tokens:** RS256 access tokens signed with a PEM key pair (`PemKeyUtils`, `JwtTokenService`), `kid` = RFC 7638 thumbprint. Opaque refresh tokens stored only as SHA-256 hashes, rotated on use, revoked with a reason; a long-rotated token presented again ends all sessions of that user. Expired token rows are purged hourly.
- **Accounts:** public registration creates passengers only; staff roles need an ADMIN token. The first admin is made by hand (README). Passwords: BCrypt, 12-72 bytes, same 401 for unknown user / wrong password / disabled account.
- **Shared contract:** `ApiResponse` / `ApiError` in `common-module` replace `ProblemDetail` in every service, including the gateway. Fixed messages, never echoing client input.
- **Gateway:** permits `POST` on the six public auth paths; everything else under `/api/v1/**` needs a token.
- **Sample data:** changeset `003-sample-data.sql` creates five dummy accounts (`dummy.passenger`, `dummy.passenger2`, `dummy.agent`, `dummy.dispatcher`, `dummy.admin`, password `dummy-password-1`). It only runs in the Liquibase `dev` context, which compose and the tests set; the default context is `schema`, so a plain start does not create them.
- **Postman:** `postman/` holds a collection and a local environment, organised by API feature: 1 Register (passenger, staff with an ADMIN token, refusals), 2 Login, 3 Refresh token, 4 Forgot password, 5 Reset password, 6 Logout, 7 Gateway rules, 8 the identity-service on port 1112 (44 requests). The first version of the collection (26 requests, 40 assertions) passed with newman against the composed stack; the reorganised version was checked for valid JSON, script syntax and variables only and has **not** been run live yet.
- **Compose and tooling:** both compose files share the project name `aero-baggage-tracer` (the name the main checkout already used, so existing volumes keep working); the identity-service is published on 1112; PostgreSQL runs on a new `postgres-18-data` volume (the old `postgres-data` volume came from an Alpine/version 17 image and made the current image exit, so nothing listened on 5433). `observability/push-jacoco.ps1` now runs `mvn verify` itself before pushing coverage (`-SkipBuild`, `-MavenArgs`).
- **Tests:** 403 in identity-service on a shared Testcontainers PostgreSQL (`AbstractBaseServiceTest`, `AbstractRestControllerTest`, `AbstractIntegrationTest`), including the role x endpoint authorization matrix, a telemetry-has-no-secrets test and concurrency tests for refresh and reset.

## Known gaps (read before merging)

Backlog acceptance criteria not met or only partly met:

- **Key rotation / "rotation does not invalidate unexpired tokens": not implemented.** A static PEM pair gives one `kid`; replacing the pair needs a restart and invalidates tokens signed by the old one (at most 15 minutes plus the gateway's key cache). Needs a follow-up issue.
- **Tokens validate at gateway and resource services:** verified by tests and by a full `docker compose` run on a fresh database with the final identity-service and gateway images (sign-in through the gateway, newman against the composed stack, 26 requests / 40 assertions, 0 failures). Later commits only touched the Postman collection, compose volume and project names, and the coverage script.

Security posture to know about:

- **`forgot-password` returns the reset token in its response** (no mail service yet, as in the reference implementation). Anyone who knows a username or email can take over that account, and the answer reveals which accounts exist. **Do not expose this service to untrusted callers until the token is delivered out of band.**
- **The development key pair is in the repository** (`identity-service/src/main/resources/keys`); the service logs a warning at every start while it is used. Provide your own pair for anything that matters.
- **The `dev` Liquibase context creates five accounts with a known password**, among them an ADMIN. Compose enables it by default; never run a real deployment with it (`LIQUIBASE_CONTEXTS=schema`).
- The OpenAPI definitions and Swagger UI are on by default in the gateway and identity-service and served without a token (`DOCS_ENABLED=false` turns them off).
- No lockout/backoff; the gateway's per-address rate limit is the only brake, with no per-endpoint or per-account limit on `login` / `forgot-password`.
- `register` answers 409 for existing usernames/emails (account enumeration); refresh sessions slide with no absolute limit; creating staff trusts the roles in the caller's token (up to 15 minutes old); access tokens are not revocable before they expire.
- Compose publishes the ports on all interfaces (Eureka 8761, config-server 1111, Prometheus 9090, Grafana 3000, the gateway 8080) and keeps default credentials (`eureka`, `admin/grafana`), so anyone who can reach the host can use them, and a fake identity-service registered in Eureka could receive login traffic; images are not pinned to digests; the database user needs `CREATE` on the database for the init SQL.

Follow-up issues (GitHub #17 to #21, backlog #25 to #29): key rotation with multiple `kid`s, mail delivery for reset tokens, per-endpoint and per-account rate limiting, session and account hardening (absolute session lifetime, role re-check, registration enumeration), hardened compose defaults.
