# ADR 0007: Data migration strategy (Liquibase, expand then contract)

Status: accepted. First applied to `journey.scan_audit.scan_source` (the only service changed and tested so far); the other four services follow the same rules for their next change.

## Context

Every service owns one PostgreSQL schema and changes it only through its own Liquibase changelog. Hibernate runs with `ddl-auto: validate`, so an entity that does not match the schema stops the service at start. Services run with several replicas and are updated one at a time, so for a while an old and a new version of the same service use the same tables.

A change that adds a mandatory column to a table that already holds rows can go wrong in three ways: the old version keeps inserting rows without the column, a backfill rewrites history that must not change, or the constraint is applied before every row satisfies it. The case study (acceptance test AT-17) asks for proof that migrations run over preloaded data without losing a row.

## Decision

1. **One changelog per service and schema.** `db/changelog/db.changelog-master.yaml` includes numbered SQL files (`001-...sql`, `002-...sql`, one or more changesets each). Liquibase's own tables live in the service's schema (`liquibase-schema` is set in each service's `application.yml`), so no service sees another's history. Running the changelog again applies nothing.
2. **A changeset is never edited after it has run anywhere.** A change is a new changeset. Liquibase checksums catch an edit, and an edited V1 would give a fresh database a different schema from a running one.
3. **Expand, then contract.** A mandatory column takes two changesets (called V2 and V3 in the case study; the numbers are the next two files of the service). Against a running system they belong in two releases, so that every replica writes the column before it becomes mandatory. Here both are in the changelog from the start, so a fresh database and the demo get them back to back; a service with live replicas would ship 006 one release after 005:
   - **V2 (expand):** add the column nullable and without a default, backfill existing rows, then set a default so an old application's inserts still get a value (nullability alone lets the old insert succeed; the default makes the value valid for V3). The application starts writing the column.
   - **V3 (contract):** a precondition counts the rows that still have no value and **halts** the migration if there is any (`onFail:HALT`), and only then sets `NOT NULL`. Nothing is half applied.
   Because V2 sets the default, an old replica that omits the column still inserts a valid row even in the short window when V3 follows V2 immediately; the V3 precondition then only fails for an explicit `NULL`.
   The default is set after the backfill on purpose: a default given at `ADD COLUMN` makes PostgreSQL report it for old rows without writing them, and there would be nothing left to validate.
4. **A backfill touches only the new column and says so.** For append-only tables (`journey.scan_audit` refuses UPDATE, DELETE and TRUNCATE by trigger) the backfill switches off the one UPDATE/DELETE trigger inside the changeset's transaction and switches it on again before the changeset ends. The table is locked for the whole transaction, so no other session sees it unprotected. The TRUNCATE trigger is never touched. A test proves the protection is back.
5. **Every changeset has a `--rollback`** (see [the rollback recipe](../migrations/README.md)). A rollback that would destroy data (dropping a column that has been written) is documented as destructive, not hidden.
6. **Evidence on real data.** AT-17 (`ScanSourceMigrationTest`) runs the real changelog against PostgreSQL in Testcontainers: V1 is applied, legacy rows are loaded, V2 and V3 are applied, and the row count and a checksum of every old column of the six tables must be equal before and after (an update that rewrites a value to itself would not show, and concurrent writes during the migration are not covered). [The demo script](../migrations/zero-data-loss-demo.ps1) shows the same with a larger data set.

### Constraints that carry business rules

| Rule | Where | Constraint |
|------|-------|------------|
| Unique bag tag | `journey.baggage` | `uq_baggage_bag_tag` |
| Source event id (AT-03) | `journey.scan_audit`, `journey.processed_event` | `uq_scan_audit_event_id`, primary key on `event_id` |
| BR-01, BR-07 | `recovery.recovery_case` | unique partial index `uq_recovery_case_active (bag_tag, leg_id) WHERE state NOT IN ('RESOLVED','CLOSED_AS_UNRECOVERABLE','CANCELLED')` |
| BR-05 | `recovery.state_transition` | `uq_state_transition_version (case_id, version_after)` |
| BR-08 | `notification.notification` | `uq_notification_recipient_event_template (recipient, event_id, template)` |
| Concurrent change | `recovery.recovery_case`, `recovery.recovery_policy`, `notification.notification` | `version bigint NOT NULL`; recovery changes it with a guarded `UPDATE ... WHERE version = :expected`, notification also has a JPA `@Version` |

### Index choices

An index exists for one query that the service runs, not for a column that "might be searched". A partial index is used when the query reads only a small part of a growing table.

| Index | Query it serves |
|-------|-----------------|
| `journey.ix_baggage_passenger_ref (passenger_ref)` | a passenger lists their bags (ownership check, AT-15) |
| `journey.ix_scan_audit_bag_time (bag_tag, occurred_at, ingested_at)` | the scan history of one bag, in event-time order |
| `journey.ix_outbox_event_pending (created_at)` (partial, `published_at IS NULL AND parked_at IS NULL`) | the relay's claim of unpublished events; the table is mostly published rows |
| `recovery.ix_outbox_event_pending_seq`, `capacity.ix_outbox_event_pending_seq (seq)` (same predicate) | the same claim, in order of `seq` (recovery replaced its `created_at` index in `009`) |
| `recovery.ix_recovery_case_state_created (state, created_at, id)` | the case list filtered by state, oldest first |
| `recovery.ix_recovery_case_hold (reserved_at)` (partial, reservation held) | the sweeper that releases timed-out capacity holds (AT-12) |
| `capacity.ix_capacity_reservation_resource (resource_id)` | the reservations of one resource |
| `notification.ix_notification_due (next_attempt_at)` (partial, `PENDING`) | the dispatcher's due notifications |
| `identity.ix_refresh_token_expiry`, `ix_password_reset_token_expiry` | the purge of expired tokens |

## Consequences

- The default stays after V3. A future writer that forgets the column is labelled with it (`API`) instead of failing. Drop the default in a later changeset once the application always sets the column, if a failure is preferred.
- A mandatory column costs two changesets and, in practice, two releases. This is the price of never refusing an old replica's insert.
- V2's backfill is one `UPDATE`: it holds a lock on the table and writes every row. For a table of hundreds of millions of rows it must be done in batches by a job, and V3 stays the guard. The demo and the test are sized for the case-study data, not for that.
- `SET NOT NULL` scans the table once under a lock. PostgreSQL 12+ skips the scan when a validated `CHECK (col IS NOT NULL)` exists; add that first if a table ever gets large enough for the scan to matter.
- Disabling a trigger needs table ownership. The services connect as the schema owner; a stricter role split would run this changeset as a migration role.

## Alternatives considered

- **`ADD COLUMN ... NOT NULL DEFAULT 'API'` in one step.** Fast in PostgreSQL 11+ and correct for this one column, but it skips the backfill and validation the case study asks to demonstrate, and it does not work for a value that has to be computed from other columns.
- **Hibernate `ddl-auto: update`.** Rejected: it cannot backfill, cannot be reviewed, and differs per environment.
- **Backfilling from the application on start.** Rejected: with several replicas it needs a lock of its own, and the schema would not say whether the data is valid.
- **Dropping the append-only trigger for good on `scan_audit`.** Rejected: history protection must not depend on a migration being careful.
