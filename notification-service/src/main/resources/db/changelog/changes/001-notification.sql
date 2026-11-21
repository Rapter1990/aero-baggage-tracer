--liquibase formatted sql

--changeset notification:001-notification
-- One row per (recipient, case event, template) (BR-08, AT-13; ADR 0005). The unique constraint is
-- the only deduplication: it holds under any number of replicas, and a delivery retry updates the
-- row, never inserts one. Only the case id, bag tag, state and version are kept from the event: the
-- free-text reason is never stored.
CREATE TABLE notification.notification (
    id               uuid         PRIMARY KEY,
    -- The handler's token subject (an opaque identity id).
    recipient        varchar(100) NOT NULL,
    event_id         varchar(100) NOT NULL,
    template         varchar(40)  NOT NULL,
    case_id          uuid         NOT NULL,
    bag_tag          varchar(32)  NOT NULL,
    -- Null when the producer sent a state this service does not know yet (template CASE_UPDATED).
    state            varchar(32),
    case_version     bigint       NOT NULL,
    occurred_at      timestamptz  NOT NULL,
    correlation_id   varchar(64),
    status           varchar(16)  NOT NULL DEFAULT 'PENDING',
    attempts         int          NOT NULL DEFAULT 0,
    -- When the next attempt is due; while a replica holds a claim, the end of its lease. Every
    -- time in this table is the database's now() (ADR 0005, decision 9), never a replica's clock.
    next_attempt_at  timestamptz  NOT NULL DEFAULT now(),
    -- An error code or exception class name only, never a provider message.
    last_error       varchar(100),
    sent_at          timestamptz,
    -- When the last attempt failed (the dead letter's occurredAt).
    failed_at        timestamptz,
    -- Set only after Kafka acknowledged the dead letter.
    dead_lettered_at timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    -- Optimistic lock: the claim raises it, and the finish only applies to the version it claimed.
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_recipient_event_template UNIQUE (recipient, event_id, template),
    CONSTRAINT ck_notification_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_state CHECK (state IS NULL OR state IN
        ('OPEN', 'ASSESSING', 'RESERVED', 'IN_TRANSIT', 'RESOLVED', 'CLOSED_AS_UNRECOVERABLE', 'CANCELLED')),
    CONSTRAINT ck_notification_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notification_case_version CHECK (case_version >= 0),
    CONSTRAINT ck_notification_version CHECK (version >= 0),
    CONSTRAINT ck_notification_sent_at CHECK (sent_at IS NULL OR status = 'SENT'),
    CONSTRAINT ck_notification_failed_at CHECK (failed_at IS NULL OR status = 'FAILED'),
    CONSTRAINT ck_notification_dead_lettered_at CHECK (dead_lettered_at IS NULL OR status = 'FAILED')
);

-- A user's own list, newest first (GET /api/v1/notifications), matching its ORDER BY.
CREATE INDEX ix_notification_recipient_created ON notification.notification (recipient, created_at DESC, id DESC);

-- The dispatcher's claim: pending rows by due time.
CREATE INDEX ix_notification_due ON notification.notification (next_attempt_at) WHERE status = 'PENDING';

-- The dead-letter publisher's claim: failed rows Kafka has not acknowledged yet.
CREATE INDEX ix_notification_undelivered_dead_letter ON notification.notification (next_attempt_at)
    WHERE status = 'FAILED' AND dead_lettered_at IS NULL;

-- The retention purge: finished rows by age.
CREATE INDEX ix_notification_purge ON notification.notification (created_at)
    WHERE status = 'SENT' OR (status = 'FAILED' AND dead_lettered_at IS NOT NULL);
--rollback DROP TABLE notification.notification;
