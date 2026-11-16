--liquibase formatted sql

--changeset journey:001-baggage-and-itinerary
-- A registered bag, its itinerary snapshot and the authoritative latest state. The latest_*
-- columns are the state of the newest accepted scan (by event time); they are written only by
-- ScanIngestionService, with a guarded UPDATE so an older scan can never overwrite a newer one.
-- passenger_ref is the pseudonymous reference from the token ("P-" + digits), never a name.
CREATE TABLE journey.baggage (
    id                  uuid        PRIMARY KEY,
    bag_tag             varchar(10) NOT NULL,
    passenger_ref       varchar(22) NOT NULL,
    registered_at       timestamptz NOT NULL DEFAULT now(),
    latest_scan_id      uuid,
    latest_airport      varchar(3),
    latest_checkpoint   varchar(20),
    latest_occurred_at  timestamptz,
    latest_device_sequence bigint,
    CONSTRAINT uq_baggage_bag_tag UNIQUE (bag_tag),
    CONSTRAINT ck_baggage_bag_tag CHECK (bag_tag ~ '^[0-9]{10}$')
);
CREATE INDEX ix_baggage_passenger_ref ON journey.baggage (passenger_ref);

-- The itinerary as it was when the bag was registered. Never updated afterwards (a snapshot).
CREATE TABLE journey.itinerary (
    id         uuid        PRIMARY KEY,
    baggage_id uuid        NOT NULL REFERENCES journey.baggage (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_itinerary_baggage UNIQUE (baggage_id)
);

CREATE TABLE journey.itinerary_leg (
    id            uuid        PRIMARY KEY,
    itinerary_id  uuid        NOT NULL REFERENCES journey.itinerary (id),
    leg_no        int         NOT NULL,
    flight_number varchar(8)  NOT NULL,
    origin        varchar(3) NOT NULL,
    destination   varchar(3) NOT NULL,
    departure_at  timestamptz NOT NULL,
    arrival_at    timestamptz NOT NULL,
    CONSTRAINT uq_itinerary_leg_no UNIQUE (itinerary_id, leg_no),
    CONSTRAINT ck_itinerary_leg_airports CHECK (origin <> destination),
    CONSTRAINT ck_itinerary_leg_chronology CHECK (arrival_at > departure_at)
);
--rollback DROP TABLE journey.itinerary_leg; DROP TABLE journey.itinerary; DROP TABLE journey.baggage;
