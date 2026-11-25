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

## Rollback recipe

Every changeset carries a `--rollback`. Roll back with Liquibase against the service's schema (`default-schema` and `liquibase-schema` are both the service's schema name, for example `journey`).

1. **Stop the writers of the new version.** Scale the service to the old image first; a rollback under a new-version replica fails Hibernate's `validate` on its next start.
2. **Take a backup of the schema.** `pg_dump --schema=journey --format=custom`. Do this even for a rollback that "only drops a column".
3. **Preview, then roll back, one changeset at a time:**
   ```powershell
   liquibase rollback-count-sql --count=1
   liquibase rollback-count --count=1
   ```
   Roll back V3 first (`006`: `DROP NOT NULL`), then V2 (`005`: `DROP COLUMN scan_source`). Liquibase removes the `databasechangelog` row of each, so a later `update` applies them again.
   The two rollbacks (V3, then V2) are run by `ScanSourceMigrationTest` (rollback, check the old rows, migrate again).
4. **Start the old image** and check `GET /actuator/health`.

### Limits

| Change | Rollback | Data lost |
|--------|----------|-----------|
| V3 `SET NOT NULL` | `DROP NOT NULL` | none |
| V2 `ADD COLUMN` + backfill | `DROP COLUMN` | the column's values. They were derived (`API` for every row up to now), but any value the new version wrote after the rollout point is gone. Restore from the backup if it matters. |
| Dropping a column or table (a future contract step) | none that restores data | all of it. Take the backup first, and expect to restore it rather than roll back. |
| A backfill that overwrote old values | none | the old values. The V2 here never does: it only fills `NULL`. |
| A changeset whose `--rollback` is a comment or missing | `rollback` refuses | n/a. Fix forward with a new changeset. |

A rollback is for the window between a release and its first writes. After that, prefer a new changeset that fixes forward (ADR 0007, decision 2).
