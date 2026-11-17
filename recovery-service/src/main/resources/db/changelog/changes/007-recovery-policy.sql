--liquibase formatted sql

--changeset recovery:007-recovery-policy
-- The recovery policy an admin can change: how many minutes a transfer needs at least. With less
-- time left a bag is rerouted instead of expedited (see the action strategy). One row, DEFAULT,
-- seeded with the 5 minutes that were configured before; the key leaves room for per-airline
-- policies. PostgreSQL is the source of truth: Redis only caches it, and the service reads this
-- row whenever Redis is unavailable. version is the optimistic lock of an admin's change.
CREATE TABLE recovery.recovery_policy (
    policy_key                 varchar(32)  PRIMARY KEY,
    minimum_expedite_minutes   int          NOT NULL,
    version                    bigint       NOT NULL DEFAULT 1,
    updated_by                 varchar(100) NOT NULL,
    updated_at                 timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_recovery_policy_minutes CHECK (minimum_expedite_minutes BETWEEN 0 AND 720)
);

INSERT INTO recovery.recovery_policy (policy_key, minimum_expedite_minutes, version, updated_by)
VALUES ('DEFAULT', 5, 1, 'system');
--rollback DROP TABLE recovery.recovery_policy;
