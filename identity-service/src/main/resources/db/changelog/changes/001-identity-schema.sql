--liquibase formatted sql

--changeset identity:001-users-and-roles
-- Users and their roles. Usernames are stored lower-case so "Admin" and "admin" can never be two
-- accounts. passenger_ref is a pseudonymous reference ("P-" + 20 digits), never an email or name.
CREATE TABLE identity.app_user (
    id            uuid         PRIMARY KEY,
    username      varchar(64)  NOT NULL,
    password_hash varchar(100) NOT NULL,
    passenger_ref varchar(22),
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT uq_app_user_passenger_ref UNIQUE (passenger_ref),
    CONSTRAINT ck_app_user_username_lower CHECK (username = lower(username)),
    CONSTRAINT ck_app_user_passenger_ref CHECK (passenger_ref ~ '^P-[0-9]{20}$')
);

CREATE TABLE identity.user_role (
    user_id uuid        NOT NULL REFERENCES identity.app_user (id) ON DELETE CASCADE,
    role    varchar(20) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_role_known CHECK (role IN ('PASSENGER', 'AGENT', 'DISPATCHER', 'ADMIN'))
);
--rollback DROP TABLE identity.user_role; DROP TABLE identity.app_user;

--changeset identity:002-auth-audit
-- Append-only record of authentication events. It deliberately has no column for a password,
-- token or any other secret. user_id has no foreign key so the history outlives the user.
CREATE TABLE identity.auth_audit (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz  NOT NULL DEFAULT now(),
    event_type  varchar(40)  NOT NULL,
    outcome     varchar(10)  NOT NULL,
    username    varchar(128),
    user_id     uuid,
    CONSTRAINT ck_auth_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
);

CREATE INDEX ix_auth_audit_occurred_at ON identity.auth_audit (occurred_at);
CREATE INDEX ix_auth_audit_username ON identity.auth_audit (username, occurred_at);
--rollback DROP TABLE identity.auth_audit;

--changeset identity:003-auth-audit-immutable splitStatements:false
-- Entries can be added but never changed, removed or truncated (also not by the application user).
CREATE FUNCTION identity.reject_auth_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'identity.auth_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_auth_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON identity.auth_audit
    FOR EACH ROW EXECUTE FUNCTION identity.reject_auth_audit_change();

CREATE TRIGGER trg_auth_audit_no_truncate
    BEFORE TRUNCATE ON identity.auth_audit
    FOR EACH STATEMENT EXECUTE FUNCTION identity.reject_auth_audit_change();
--rollback DROP FUNCTION identity.reject_auth_audit_change() CASCADE;
