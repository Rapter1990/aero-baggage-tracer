--liquibase formatted sql

--changeset recovery:010-hold-confirmation
-- When the capacity-service was told that this case kept its hold. The capacity-service expires
-- holds nobody confirmed, so a RESERVED or IN_TRANSIT case whose hold is not confirmed yet is
-- confirmed again by the sweeper. Null until confirmed; a new hold starts unconfirmed again.
ALTER TABLE recovery.recovery_case ADD COLUMN hold_confirmed_at timestamptz;
--rollback ALTER TABLE recovery.recovery_case DROP COLUMN hold_confirmed_at;
