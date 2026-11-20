# ADR 0003: Spring Data auditing for who and when

Status: accepted (audit-module; identity, journey and capacity services; the rebuild jobs of the search-service)

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
   its entity extend `AuditableEntity`; there is nothing to configure. A service that uses the
   module only for `SecurityAuditorAware.currentAuditor()` sets `aerotrace.audit.jpa-auditing=false`
   (capacity does), and so can a service that declares its own `@EnableJpaAuditing`.
2. **Four columns:** `created_by`, `created_at`, `updated_by`, `updated_at`
   (`@CreatedBy`, `@CreatedDate`, `@LastModifiedBy`, `@LastModifiedDate`). `created_by`/`updated_by`
   are `varchar(100) NOT NULL DEFAULT 'system'`, the times `timestamptz`, UTC like every other
   timestamp here, taken from the service's own `Clock` (so a fixed clock in a test moves them all
   together). On creation all four are set, so an unchanged row has `updated_at = created_at`.
3. **Who is acting** (`SecurityAuditorAware`):
   - a signed-in user: the subject of the token. The identity-service issues the **user's id (a UUID)**
     as the subject, so the column holds that opaque id, not the username. It stays valid when a
     username changes and names the account in the identity-service. To show a name, look the id up
     there;
   - `service` for the recovery-service sweeper (service token);
   - `anonymous` for a request with no credentials;
   - `system` when there is no request (a job, a consumer) and for rows that existed before
     auditing.

   The value is cut to 100 characters and is never a token. It is read from the thread's security
   context: work handed to another thread (an `@Async` call) must carry the context along (for
   example `DelegatingSecurityContextExecutor`), or it is recorded as `system`.
4. **Where it applies**
   - JPA-saved entities extend `AuditableEntity`: identity `AppUserEntity`, journey `BaggageEntity`
     and `ItineraryEntity`. Each got a migration that adds the columns with `'system'` defaults and
     backfills `updated_at` (for a bag, `created_at` starts as `registered_at`, which the column
     duplicates on purpose: `registered_at` is the domain fact, `created_at` the audit fact).
   - **Native or bulk statements do not fire JPA listeners.** Capacity writes its tables with guarded
     native SQL (that is what makes reservations atomic), so those statements write the columns
     themselves with `SecurityAuditorAware.currentAuditor()`:
     - a **resource** records `created_by`/`created_at` and `updated_by`/`updated_at` (every hold,
       release or resize that moves its slots);
     - a **hold** records who created it (`created_by`/`created_at`), who confirmed it
       (`confirmed_by`/`confirmed_at`) and who closed it (`released_by`/`released_at`; `system` when
       it expired). Each is written once, so a hold has no `updated_*`.
   - **Recovery is not changed.** A case already has `created_by`, `created_at` and `updated_at`,
     and the policy has `updated_by`/`updated_at`. Every state move and every assignment writes a
     `state_transition` row with the actor, the time and the reason (BR-05), which is a better trail
     than one `updated_by`. What it does not attribute is bookkeeping done by the service itself:
     recording a hold, confirming it, flagging a scanner anomaly. A column `updated_by` on the case
     would have to be written by eight bulk updates for little gain, so it is left out until
     someone needs "who touched the case last" apart from its history.
   - **Search-service (Elasticsearch):** the rebuild job documents in `search-jobs` are saved through
     a Spring Data repository, so `@EnableElasticsearchAuditing` fills `createdAt`, `createdBy`,
     `lastModifiedAt` and `lastModifiedBy` with the same `SecurityAuditorAware` (the module is used
     without its JPA dependencies) and the service `Clock`. `createdBy` is the admin's token
     subject (the job is created on the request thread); the worker thread that finishes the job
     is `system`. The document is rebuilt on every save, so the store carries the creation fields
     over, and the document counts as new while it has no `createdAt` (an assigned id would
     otherwise make Spring Data take it for existing). **The read models carry no audit fields:**
     they are written by bulk scripted upserts, which fire no auditing callbacks, and a rebuild
     recreates every document (ADR 0004). The rebuild lock and the idempotency keys are
     bookkeeping and are not audited.
   - **Not audited:** token, outbox, dedupe, throttle, snapshot and audit-log tables are system
     bookkeeping or already an audit trail (`auth_audit`, `scan_audit`, `state_transition`).
     `itinerary_leg` is part of the itinerary snapshot and is never changed after it is created.

## Consequences

- A changed row always says who touched it last, without every service writing the code.
- The values come from the token, so they are as trustworthy as the token; a row written by a job
  says `system`, not a person.
- A new table that is written by bulk or native SQL does not get auditing for free: its statements
  must set the columns (see capacity).
- Old rows say `system`: the migration cannot know who made them. For a capacity resource that
  existed before the migration, `created_at` is its `updated_at` (the column did not exist), so it is
  approximate; it is exact for everything created afterwards.
- A change that touches only a collection of an entity (the roles of a user, the legs of an
  itinerary) does not fire the update listener, so `updated_*` would not move. Nothing edits either
  today; if roles become editable, set `updatedAt` explicitly or version the user.
- Liquibase: every change here is a new changeset. A changeset that was applied is never edited,
  not even a comment (the checksum would change).

## Not decided here

An audit *history* (every previous value of a row) is not stored; only the latest change is. That
would be a separate decision (for example Hibernate Envers).
