--liquibase formatted sql

--changeset recovery:001-recovery-case
-- A recovery case for one bag and one itinerary leg. state and version change only through a
-- guarded UPDATE (WHERE version = :expected), so two concurrent moves cannot both succeed; the
-- version starts at 1. bag_tag and leg_id are plain values: the bag lives in the journey-service,
-- whose database this service never reads.
CREATE TABLE recovery.recovery_case (
    id              uuid         PRIMARY KEY,
    bag_tag         varchar(10)  NOT NULL,
    leg_id          varchar(32)  NOT NULL,
    state           varchar(30)  NOT NULL,
    version         bigint       NOT NULL DEFAULT 1,
    reason          varchar(500) NOT NULL,
    idempotency_key varchar(128),
    created_by      varchar(100) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_recovery_case_bag_tag CHECK (bag_tag ~ '^[0-9]{10}$'),
    CONSTRAINT ck_recovery_case_state CHECK (state IN
        ('OPEN', 'ASSESSING', 'RESERVED', 'IN_TRANSIT', 'RESOLVED', 'CLOSED_AS_UNRECOVERABLE', 'CANCELLED')),
    CONSTRAINT uq_recovery_case_idempotency_key UNIQUE (idempotency_key)
);

-- BR-01: at most one ACTIVE case per bag and leg. The database decides, so parallel requests (and
-- any number of replicas) can never create two. A case that has reached a final state frees the
-- pair; a new case is a new row with a new id (BR-07: a final case is never reopened).
CREATE UNIQUE INDEX uq_recovery_case_active ON recovery.recovery_case (bag_tag, leg_id)
    WHERE state NOT IN ('RESOLVED', 'CLOSED_AS_UNRECOVERABLE', 'CANCELLED');

-- The list endpoint filters by state and pages oldest first (created_at, id).
CREATE INDEX ix_recovery_case_state_created ON recovery.recovery_case (state, created_at, id);

-- BR-05: every move records who, when and why. Insert-only; the first row is the opening.
CREATE TABLE recovery.state_transition (
    id            uuid         PRIMARY KEY,
    case_id       uuid         NOT NULL REFERENCES recovery.recovery_case (id),
    from_state    varchar(30),
    to_state      varchar(30)  NOT NULL,
    actor         varchar(100) NOT NULL,
    occurred_at   timestamptz  NOT NULL,
    reason        varchar(500) NOT NULL,
    version_after bigint       NOT NULL,
    CONSTRAINT uq_state_transition_version UNIQUE (case_id, version_after)
);
--rollback DROP TABLE recovery.state_transition; DROP TABLE recovery.recovery_case;
