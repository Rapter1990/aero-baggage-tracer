# Service discovery

## Eureka (Compose / dev)

`discovery-server` is a standalone Eureka server on port `8761` (dashboard at `/`, health at `/actuator/health`).
Every service registers itself on startup; the API gateway resolves `lb://<service-name>` through the registry.

Compose health check (same as the image's `HEALTHCHECK`):

```yaml
healthcheck:
  test: ["CMD", "curl", "-fsS", "http://localhost:8761/actuator/health"]
  interval: 10s
  retries: 5
```

### Client configuration template

Add to each service's `application.yml` (`spring-cloud-starter-netflix-eureka-client` on the classpath):

```yaml
spring:
  application:
    name: <service-name>          # becomes the lb:// id, e.g. journey-service

eureka:
  client:
    service-url:
      defaultZone: ${EUREKA_URL:http://localhost:8761/eureka/}
  instance:
    instance-id: ${spring.application.name}:${spring.cloud.client.hostname}:${server.port}
    prefer-ip-address: true
    lease-renewal-interval-in-seconds: 10     # heartbeat
    lease-expiration-duration-in-seconds: 30  # evict after 3 missed heartbeats
    health-check-url-path: /actuator/health
```

In Compose set `EUREKA_URL=http://discovery-server:8761/eureka/`.

## Compose vs Minikube

| | Docker Compose (dev) | Minikube / Kubernetes |
|---|---|---|
| Discovery | Eureka registry | Kubernetes `Service` + cluster DNS |
| Gateway routing | `lb://journey-service` via Eureka | `http://journey-service:8080` via DNS |
| `discovery-server` | deployed | **not deployed**; clients run with `eureka.client.enabled=false` |
| Health | Compose `healthcheck` | readiness/liveness probes (`/actuator/health/readiness`) |

Use Eureka for local development and Compose; use native Kubernetes discovery in Minikube, where the platform already
provides service registration, load balancing and health-based endpoint removal.
