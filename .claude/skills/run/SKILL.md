---
name: run
description: Start the AeroTrace stack (Docker Compose, later Minikube) and confirm services are healthy, so a change can be seen working.
---

1. Build: `mvn -q -DskipTests package`.
2. Start infrastructure and services with Docker Compose once issue #16 lands (`docker compose up -d`); until then run a single module with `mvn -pl <module> spring-boot:run`.
3. Wait for Actuator health (`/actuator/health`) on each service and for registration in Eureka.
4. Smoke-test through the gateway under `/api/v1` with a token per role.
5. Report which services are up, which are not, and the log lines for any that failed.
