--liquibase formatted sql

--changeset recovery:008-assignment-and-move-keys
-- The handler of a case: the subject of the agent or dispatcher it is assigned to. An opaque id
-- from the token (the identity-service is not asked), never a name. Nullable: a case is
-- unassigned until someone takes it, and existing rows stay valid.
ALTER TABLE recovery.recovery_case ADD COLUMN assigned_to varchar(100);

-- The Idempotency-Key of a move. It is kept on the history row the move wrote, and is unique per
-- case, so a retry (a lost answer, a second click, another replica) finds the original move and
-- gets its answer instead of a second effect. Nullable: moves made without a key, and moves made
-- by the system, have none.
ALTER TABLE recovery.state_transition ADD COLUMN idempotency_key varchar(128);
CREATE UNIQUE INDEX uq_state_transition_idempotency ON recovery.state_transition (case_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
--rollback DROP INDEX recovery.uq_state_transition_idempotency; ALTER TABLE recovery.state_transition DROP COLUMN idempotency_key; ALTER TABLE recovery.recovery_case DROP COLUMN assigned_to;
