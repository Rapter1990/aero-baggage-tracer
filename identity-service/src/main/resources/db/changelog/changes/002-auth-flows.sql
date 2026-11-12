--liquibase formatted sql

--changeset identity:002-user-profile
-- Registration collects an email address and a full name. Both are optional in the table so users
-- created before registration existed (and the bootstrap admin) stay valid; the API requires them
-- for self-registration. Emails are stored lower-case and are unique.
ALTER TABLE identity.app_user ADD COLUMN email     varchar(150);
ALTER TABLE identity.app_user ADD COLUMN full_name varchar(150);
ALTER TABLE identity.app_user ADD CONSTRAINT ck_app_user_email_lower CHECK (email = lower(email));
CREATE UNIQUE INDEX uq_app_user_email ON identity.app_user (email);
--rollback DROP INDEX identity.uq_app_user_email; ALTER TABLE identity.app_user DROP COLUMN full_name; ALTER TABLE identity.app_user DROP COLUMN email;

--changeset identity:003-refresh-token
-- Opaque refresh tokens. Only the SHA-256 hash of a token is stored, never the token itself; a
-- token is revoked by setting revoked_at (rotation, logout, password reset, reuse detection).
CREATE TABLE identity.refresh_token (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.app_user (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_token_user ON identity.refresh_token (user_id);
--rollback DROP TABLE identity.refresh_token;

--changeset identity:004-password-reset-token
-- Single-use password reset tokens, stored as a SHA-256 hash like the refresh tokens.
CREATE TABLE identity.password_reset_token (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.app_user (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_password_reset_token_user ON identity.password_reset_token (user_id);
--rollback DROP TABLE identity.password_reset_token;
