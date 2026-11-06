---
name: architecture-review
description: Review a change or design against AeroTrace architecture rules (service-owned schemas, versioned contracts, gateway entry, Kafka conventions).
---

1. Identify the modules touched (`git diff main...HEAD --stat`).
2. Run `architect` for boundaries and coupling, `api-contract-engineer` for contracts, `database-reviewer` for persistence.
3. Verify: no JPA in `common-module`, no cross-service DB access, events versioned, idempotent consumers, correlation id propagated.
4. Output findings ranked blocker / should-fix / nit. Propose an ADR in `docs/adr/` when a decision is needed.
