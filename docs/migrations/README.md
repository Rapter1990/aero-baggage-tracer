# Database migrations: demo and rollback recipe

The strategy is in [ADR 0007](../adr/0007-data-migration-strategy.md). Each service owns one changelog under `<service>/src/main/resources/db/changelog/`.

## Zero-data-loss demo (AT-17)

Needs Docker and the `postgres:18-alpine` image.

```powershell
powershell -File docs/migrations/zero-data-loss-demo.ps1
powershell -File docs/migrations/zero-data-loss-demo.ps1 -Bags 5000 -Keep
```

It applies the journey-service changesets 001-004 (V1), loads legacy rows, prints a row count and checksum per table, applies V2 (`005-scan-source.sql`: nullable column and backfill), validates, applies V3 (`006-scan-source-not-null.sql`: `NOT NULL`), and prints the numbers again. The checksum leaves out the new column, so a changed old value shows. It ends with `PASS` or exits with 1.

The automated proof, with Liquibase itself and its precondition, is AT-17:

```powershell
mvn -pl journey-service -am test -Dtest=ScanSourceMigrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Contexts: delivery order and sample data

The journey-service reads its Liquibase contexts from `JOURNEY_LIQUIBASE_CONTEXTS` (default `expand,contract`; an empty value is treated as the default, never as "everything"). Docker Compose passes it to the journey-service with the same default: put `JOURNEY_LIQUIBASE_CONTEXTS=expand,contract,dev` in `.env` (or the shell) to start with the sample data, and `docker compose up` it on a fresh database (the sample data is skipped once the database holds bags).

| Value | Applies |
|-------|---------|
| `expand,contract` (default) | everything up to `008`; a fresh database gets V2 and V3 together |
| `expand` | the additive steps only; `006` (`NOT NULL`) is skipped. Use it for the first release of a rollout against live replicas, and do not forget the next step: nothing alerts when a rollout stays on `expand` (the entity says `nullable = false`, the database does not). Check with `SELECT count(*) FROM journey.databasechangelog WHERE id = '006-scan-source-not-null'` that the contract release applied it |
| `expand,contract` after `expand` | `006` on the next release, once every replica writes `scan_source` |
| `expand,contract,dev` | also `009`: 20 sample bags (`9000000001`-`9000000020`) with two legs and three scans each, for local work. Never set `dev` in a real deployment |

## Rollback recipe

Every changeset carries a `--rollback`. Roll back with Liquibase against the service's schema (`default-schema` and `liquibase-schema` are both the service's schema name, for example `journey`).

1. **Stop the writers of the new version.** Scale the service to the old image first; a rollback under a new-version replica fails Hibernate's `validate` on its next start.
2. **Take a backup of the schema.** `pg_dump --schema=journey --format=custom`. Do this even for a rollback that "only drops a column".
3. **Preview, then roll back, one changeset at a time:**
   ```powershell
   liquibase --defaults-file=liquibase.properties rollback-count-sql --count=1
   liquibase --defaults-file=liquibase.properties rollback-count --count=1
   ```
   `liquibase.properties` holds the connection and the schema settings, for example:
   ```properties
   changeLogFile=db/changelog/db.changelog-master.yaml
   url=jdbc:postgresql://localhost:5432/aerotrace
   username=<owner of the schema>
   password=<from the secret store>
   defaultSchemaName=journey
   liquibaseSchemaName=journey
   ```
   Roll back the newest first: the validation (`008`: nothing to undo, the constraint stays), the `CHECK` (`007`: `DROP CONSTRAINT`), V3 (`006`: `DROP NOT NULL`), then V2 (`005`: `DROP COLUMN scan_source`). Sample data (`009`) rolls back first of all. Liquibase removes the `databasechangelog` row of each, so a later `update` applies them again.
   The rollbacks of `008` to `005`, and of `009`, are run by `ScanSourceMigrationTest` (rollback, check the old rows, migrate again).
4. **Start the old image** and check `GET /actuator/health`.

### Limits

| Change | Rollback | Data lost |
|--------|----------|-----------|
| `007`/`008` `CHECK` | `DROP CONSTRAINT` (the validation has nothing to undo) | none |
| V3 `SET NOT NULL` | `DROP NOT NULL` | none |
| V2 `ADD COLUMN` + backfill | `DROP COLUMN` | the column's values. They were derived (`API` for every row up to now), but any value the new version wrote after the rollout point is gone. Restore from the backup if it matters. |
| Dropping a column or table (a future contract step) | none that restores data | all of it. Take the backup first, and expect to restore it rather than roll back. |
| A backfill that overwrote old values | none | the old values. The V2 here never does: it only fills `NULL`. |
| A changeset whose `--rollback` is a comment or missing | `rollback` refuses | n/a. Fix forward with a new changeset. |

A rollback is for the window between a release and its first writes. After that, prefer a new changeset that fixes forward (ADR 0007, decision 2).
