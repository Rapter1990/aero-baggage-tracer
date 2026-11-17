--liquibase formatted sql

--changeset recovery:006-risk-event-clock
-- The event time of the newest risk event applied to each bag and leg. A risk event older than the
-- stored time is stale (a late or replayed event) and changes nothing, so a HIGH that arrives after
-- the LOW that cleared it cannot open a false case. Written only by the risk consumer, in the same
-- transaction as its effect.
CREATE TABLE recovery.risk_event_clock (
    bag_tag     varchar(10) NOT NULL,
    leg_id      varchar(32) NOT NULL,
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (bag_tag, leg_id)
);
--rollback DROP TABLE recovery.risk_event_clock;
