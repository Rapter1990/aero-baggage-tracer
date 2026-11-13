## What this PR delivers (final state)

The commit list below is the history of the branch. Several early commits (`POST /auth/token`, seed users, key rotation, database-held keys) were replaced later; this is what the branch contains now.

- **identity-service** (servlet, Spring Boot 4, JPA, Liquibase schema `identity`) listening on **port 1112** (published by `docker-compose.yml`; the gateway reaches it through Eureka) with `AuthController`: `register`, `login`, `refresh-token`, `forgot-password`, `reset-password`, `logout` under `/api/v1/auth`.
- **Tokens:** RS256 access tokens signed with a PEM key pair (`PemKeyUtils` in `common-module`, `JwtTokenService`); the issuer is a fixed constant (`JwtClaims.ISSUER`). **There is no JWKS endpoint, no `kid`, and no `JWT_ISSUER` / `JWK_SET_URI` / `IDENTITY_ISSUER` setting:** the api-gateway verifies tokens with the RSA public key, taken from `JWT_PUBLIC_KEY_LOCATION` / `JWT_PUBLIC_KEY` or, by default, from a copy of identity-service's `keys/public.pem` that is packaged into the gateway at build time. Opaque refresh tokens stored only as SHA-256 hashes, rotated on use, revoked with a reason; a long-rotated token presented again ends all sessions of that user. Expired token rows are purged hourly.
- **Accounts:** public registration creates passengers only; staff roles need an ADMIN token. The first admin is made by hand (README). Passwords: BCrypt, 12-72 bytes, same 401 for unknown user / wrong password / disabled account.
- **Shared contract:** `ApiResponse` / `ApiError` in `common-module` replace `ProblemDetail` in every service, including the gateway. Fixed messages, never echoing client input.
- **Gateway:** permits `POST` on the six public auth paths; everything else under `/api/v1/**` needs a token.
- **Sample data:** changeset `003-sample-data.sql` creates five dummy accounts (`dummy.passenger`, `dummy.passenger2`, `dummy.agent`, `dummy.dispatcher`, `dummy.admin`, password `dummy-password-1`). It only runs in the Liquibase `dev` context, which compose and the tests set; the default context is `schema`, so a plain start does not create them.
- **Postman:** `postman/` holds a collection and a local environment, organised by API feature: 1 Register (passenger, staff with an ADMIN token, refusals), 2 Login, 3 Refresh token, 4 Forgot password, 5 Reset password, 6 Logout, 7 Gateway rules, 8 the identity-service on port 1112 (44 requests). Run with newman against the composed stack (fresh database, final images): 47 requests, 61 assertions, 0 failures.
- **Compose and tooling:** both compose files share the project name `aero-baggage-tracer` (the name the main checkout already used, so existing volumes keep working); the identity-service is published on 1112; PostgreSQL runs on a new `postgres-18-data` volume (the old `postgres-data` volume came from an Alpine/version 17 image and made the current image exit, so nothing listened on 5433). `observability/push-jacoco.ps1` now runs `mvn verify` itself before pushing coverage (`-SkipBuild`, `-MavenArgs`).
- **Tests:** 403 in identity-service on a shared Testcontainers PostgreSQL (`AbstractBaseServiceTest`, `AbstractRestControllerTest`, `AbstractIntegrationTest`), including the role x endpoint authorization matrix, a telemetry-has-no-secrets test and concurrency tests for refresh and reset.

## Known gaps (read before merging)

Backlog acceptance criteria not met or only partly met:

- **Key rotation / "rotation does not invalidate unexpired tokens" and the JWKS endpoint: not implemented, by decision.** The service uses one static PEM pair and the gateway holds a copy of the public key: replacing the pair means restarting identity-service and the gateway, and tokens signed with the old pair stop verifying. The backlog's "JWKS endpoint" and "multiple active `kid`s" are therefore not met; follow-up issue #17 (backlog #25) is about rotation and needs rescoping.
- **Tokens validate at gateway and resource services:** verified by tests and by a full `docker compose` run on a fresh database with the final images: a valid token is accepted by the gateway (it forwards the request), a forged or missing one gets 401 from the gateway.

Security posture to know about:

- **`forgot-password` returns the reset token in its response** (no mail service yet, as in the reference implementation). Anyone who knows a username or email can take over that account, and the answer reveals which accounts exist. **Do not expose this service to untrusted callers until the token is delivered out of band.**
- **The development key pair is in the repository** (`identity-service/src/main/resources/keys`); the service logs a warning at every start while it is used. Provide your own pair for anything that matters.
- **The `dev` Liquibase context creates five accounts with a known password**, among them an ADMIN. Compose enables it by default; never run a real deployment with it (`LIQUIBASE_CONTEXTS=schema`).
- The OpenAPI definitions and Swagger UI are on by default in the gateway and identity-service and served without a token (`DOCS_ENABLED=false` turns them off).
- No lockout/backoff; the gateway's per-address rate limit is the only brake, with no per-endpoint or per-account limit on `login` / `forgot-password`.
- `register` answers 409 for existing usernames/emails (account enumeration); refresh sessions slide with no absolute limit; creating staff trusts the roles in the caller's token (up to 15 minutes old); access tokens are not revocable before they expire.
- Compose publishes the ports on all interfaces (Eureka 8761, config-server 1111, Prometheus 9090, Grafana 3000, the gateway 8080) and keeps default credentials (`eureka`, `admin/grafana`), so anyone who can reach the host can use them, and a fake identity-service registered in Eureka could receive login traffic; images are not pinned to digests; the database user needs `CREATE` on the database for the init SQL.

Follow-up issues (GitHub #17 to #21, backlog #25 to #29): key rotation with multiple `kid`s, mail delivery for reset tokens, per-endpoint and per-account rate limiting, session and account hardening (absolute session lifetime, role re-check, registration enumeration), hardened compose defaults.
