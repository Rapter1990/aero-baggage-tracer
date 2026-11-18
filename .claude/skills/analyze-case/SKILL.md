---
name: analyze-case
description: Read the AeroTrace requirements in docs/requirements and turn them into issues, business rules (BR-xx) and acceptance tests (AT-xx). Use when scoping new work or checking coverage of the spec.
---

1. Read `docs/requirements/BUSINESS_RULES.md` and `docs/requirements/ACCEPTANCE_TESTS.md` (exact BR-xx and AT-xx wording) and `docs/requirements/TRACEABILITY.md`.
2. Extract: modules, topics, roles, business rules (BR-xx), acceptance tests (AT-xx), non-functional targets. Quote BR/AT wording from `docs/requirements`, never from memory.
3. Compare with `docs/issues/GITHUB_ISSUES.md`; list requirements no issue covers.
4. Delegate wording to `product-owner`. Output: a coverage table (requirement -> issue) and proposed new issues.
