# ADR-0001: common-module boundaries and packaging

- Status: Accepted
- Date: 2026-11-08
- Context issue: backlog #4 Define Common Module (GitHub #7), PR #8
- Found by: `/architecture-review` (findings B1, S3, S6, S10)

## Context

`common-module` is a dependency of every service. Spec section 4 says it holds "versioned API error
contracts, event envelopes, shared security primitives; no JPA entities". The first version pulled
`spring-webmvc` and `spring-security-oauth2-jose` onto the classpath of every consumer and shipped a
servlet filter and a `@RestControllerAdvice` that depended on component scanning.

Problems with that:

1. **The gateway is likely reactive.** Spring Cloud Gateway (WebFlux) with the Redis rate limiter
   breaks when Spring MVC is on the classpath, and the servlet filter, the MVC exception handler and
   the thread-local `SecurityContextHelper` do not work in WebFlux anyway.
2. **Nothing registered the filter or advice.** Each service would wire them differently, in a
   different order, and a service that both scanned and extended the advice got two of them.
3. **The "no JPA" rule was only a convention.**
4. **Service-specific concerns leaked in** (see Decision 5).

## Decision

1. **One artifact, stack-neutral by default.** The jar always carries only stack-neutral contract
   types: `ErrorCode`, `ApiException`, `ProblemDetails`, `EventEnvelope`, payload records, `Topics`,
   `CorrelationId`, `Role`, `JwtClaims`, `LogMasker`. Their dependencies are `spring-web`,
   `jackson-databind`, `slf4j-api` and `spring-boot-autoconfigure`.
2. **Web and security dependencies are `optional`.** `spring-webmvc`, `spring-security-core` and
   `spring-security-oauth2-jose` are declared `optional`, and `jakarta.servlet-api` is `provided`.
   A service gets them only by declaring them itself. The reactive `api-gateway` uses the contract
   types and writes its own WebFlux filter and error handler.
3. **Servlet wiring is a conditional auto-configuration.** `CommonServletAutoConfiguration` is active
   only in a servlet web application with `DispatcherServlet` on the classpath. It registers the
   `CorrelationIdFilter` (order `HIGHEST_PRECEDENCE`, so 401/403 responses carry the id), the
   `CorrelationIdInterceptor`, and a default exception advice. Every bean backs off if the service
   defines its own.
4. **No second advice.** `BaseExceptionHandler` is not annotated. A service that needs extra handlers
   extends it in its own `@RestControllerAdvice`; the default then backs off.
5. **Common holds contracts, not domain rules.** No state machines, no business policies. Enums that
   appear on the wire (`RiskLevel`, `RecoveryState`, `AnomalyType`) are shared vocabulary, owned by
   the producing service and only housed here (see ADR-0002 for how they evolve).
6. **The boundary is enforced by the build.** `maven-enforcer-plugin` fails the build if the module
   gains Hibernate, `jakarta.persistence`, Spring Data JPA, or a web starter.

## Consequences

- A servlet service adds `spring-webmvc`, `spring-security-oauth2-jose` and the servlet API itself
  (normally through `spring-boot-starter-webmvc` and the resource-server starter) and gets the
  wiring for free.
- The gateway can depend on common without inheriting Spring MVC.
- `SecurityContextHelper` and `BaseExceptionHandler` are servlet-only and thread-local; they must not
  be used from reactive code.
- A change to a released error code or payload shape needs a coordinated release of every service,
  which ADR-0002 limits to additive changes.

## Not decided here / follow-up

- **Service-owned error codes.** `ErrorCode` still mixes generic codes with business ones
  (`BAG_TAG_DUPLICATE`, `CAPACITY_EXCEEDED`, ...). The intended shape is an `ErrorCode` interface
  (`code()`, `httpStatus()`, `title()`) with a generic enum in common and each service defining its
  own codes. Not implemented in PR #8.
- `RecoveryState.isTerminal()` is recovery-service knowledge and should move there.
- 401/403 raised by the security filter chain never reach MVC advice; common should provide an
  `AuthenticationEntryPoint`, an `AccessDeniedHandler` and a `JwtAuthenticationConverter` built on
  `Role`.
- The reactive counterpart (WebFilter, error handler) is owned by the api-gateway issue.
- Whether the gateway is the WebFlux or the servlet flavor is not stated in the backlog; this ADR
  holds for either.

## Alternatives considered

- **Split into `common-contracts` and `common-servlet` artifacts.** Cleaner dependency graph, but two
  artifacts to version and release at this size. Revisit if the servlet part grows.
- **Keep MVC as a normal dependency and make the gateway servlet-based.** Rejected: ties a platform
  decision to a library convenience.
