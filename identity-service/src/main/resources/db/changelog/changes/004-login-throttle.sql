--liquibase formatted sql

--changeset identity:005-login-throttle
-- Counts failed sign-ins and password reset requests per typed name, to slow down guessing and to
-- lock a name for a limited time. The key is a SHA-256 hash of the scope and the typed name, never
-- the text itself: people paste passwords into the username box, and unknown names are counted too
-- so a locked name looks the same whether or not the account exists.
CREATE TABLE identity.login_throttle (
    key_hash          varchar(64) PRIMARY KEY,
    hits              integer     NOT NULL,
    window_started_at timestamptz NOT NULL,
    locked_until      timestamptz
);
CREATE INDEX ix_login_throttle_window ON identity.login_throttle (window_started_at);
--rollback DROP TABLE identity.login_throttle;
