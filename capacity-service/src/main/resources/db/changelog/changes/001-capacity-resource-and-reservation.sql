--liquibase formatted sql

--changeset capacity:001-capacity-resource-and-reservation
-- BR-04: a resource (a recovery cart, flight or bag-slot pool; the recovery-service uses the leg id)
-- has a fixed total and a remaining count. remaining_slots only changes through a guarded UPDATE
-- (WHERE remaining_slots >= :slots), so any number of parallel requests, on any number of
-- replicas, can never take more than is left. The CHECK is the last line of defence: even a bug
-- cannot store a negative or an over-full count. PostgreSQL is the only authority; nothing else
-- (a cache, a hint) can grant capacity.
CREATE TABLE capacity.capacity_resource (
    id              varchar(32) PRIMARY KEY,
    total_slots     integer     NOT NULL,
    remaining_slots integer     NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_capacity_resource_slots CHECK
        (total_slots >= 0 AND remaining_slots >= 0 AND remaining_slots <= total_slots)
);

-- A hold on slots of one resource. The id is chosen by the caller, so a hold whose answer was lost
-- can still be released by id, and a repeated request finds the same row. status moves only
-- HELD -> RELEASED or HELD -> EXPIRED, by a guarded UPDATE, so slots are returned exactly once.
-- confirmed_at is set when the caller says a case move kept the hold; only unconfirmed holds expire.
CREATE TABLE capacity.capacity_reservation (
    id              uuid         PRIMARY KEY,
    resource_id     varchar(32)  NOT NULL REFERENCES capacity.capacity_resource (id),
    case_id         uuid         NOT NULL,
    bag_tag         varchar(10)  NOT NULL,
    slots           integer      NOT NULL,
    status          varchar(10)  NOT NULL,
    idempotency_key varchar(128),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    confirmed_at    timestamptz,
    released_at     timestamptz,
    CONSTRAINT ck_capacity_reservation_slots CHECK (slots > 0),
    CONSTRAINT ck_capacity_reservation_bag_tag CHECK (bag_tag ~ '^[0-9]{10}$'),
    CONSTRAINT ck_capacity_reservation_status CHECK (status IN ('HELD', 'RELEASED', 'EXPIRED')),
    CONSTRAINT uq_capacity_reservation_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX ix_capacity_reservation_resource ON capacity.capacity_reservation (resource_id);

-- The expiry job looks only at holds nobody confirmed.
CREATE INDEX ix_capacity_reservation_expirable ON capacity.capacity_reservation (created_at)
    WHERE status = 'HELD' AND confirmed_at IS NULL;
--rollback DROP TABLE capacity.capacity_reservation; DROP TABLE capacity.capacity_resource;
