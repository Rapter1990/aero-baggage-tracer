# ADR 0003: Spring Data auditing for who and when

Status: accepted (audit-module; identity, journey and capacity services)

## Context

Rows that people or devices change should say who created them, who last changed them and when. The
services had this only in places and by hand: `created_at` on a few tables, `created_by` and
`updated_at` on recovery cases, `updated_by` on the recovery policy, and no user at all on users,
bags or capacity.

`common-module` must not depend on JPA (an enforcer rule bans it, and services without a database
use it), so a shared JPA base class cannot live there.

## Decision

1. **A small `audit-module`** holds `AuditableEntity`, `SecurityAuditorAware` and an
   auto-configuration that switches on `@EnableJpaAuditing`. A service adds the dependency and lets
   its entity extend `AuditableEntity`; there is nothing to configure.
2. **Four columns:** `created_by`, `created_at`, `updated_by`, `updated_at`
   (`@CreatedBy`, `@CreatedDate`, `@LastModifiedBy`, `@LastModifiedDate`). Times are UTC instants
   (`timestamptz`), like every other timestamp here. On creation all four are set, so an unchanged
   row has `updated_at = created_at`.
3. **Who is acting** (`SecurityAuditorAware`): the subject of the caller's token; `service` for the
   recovery-service sweeper (service token); `anonymous` for a request with no credentials;
   `system` when there is no request (a job, a consumer) and for rows that existed before auditing.
   The value is cut to 100 characters and is never a token.
4. **Where it applies**
   - JPA-saved entities extend `AuditableEntity`: identity `AppUserEntity`, journey `BaggageEntity`
     and `ItineraryEntity`. Each got a migration that adds the columns with `'system'` defaults and
     backfills `updated_at` (for a bag, `created_at` starts as `registered_at`).
   - **Native or bulk statements do not fire JPA listeners.** Capacity writes its tables with guarded
     native SQL (that is what makes reservations atomic), so those statements write the columns
     themselves with `SecurityAuditorAware.currentAuditor()`: `created_by`/`created_at`/`updated_by`
     on a resource, and `created_by` / `released_by` on a hold (`system` when it expired).
   - **Not changed:** the recovery-service already records who, when and why for every move in
     `state_transition` (BR-05), plus `created_by`/`updated_at` on the case and `updated_by` on the
     policy; its writes are bulk updates guarded by a version. Token, outbox, dedupe and audit-log
     tables are system bookkeeping or already an audit trail.

## Consequences

- A changed row always says who touched it last, without every service writing the code.
- The values come from the token, so they are as trustworthy as the token; a row written by a job
  says `system`, not a person.
- A new table that is written by bulk or native SQL does not get auditing for free: its statements
  must set the columns (see capacity).
- Old rows say `system`: the migration cannot know who made them.

## Not decided here

An audit *history* (every previous value of a row) is not stored; only the latest change is. That
would be a separate decision (for example Hibernate Envers).
