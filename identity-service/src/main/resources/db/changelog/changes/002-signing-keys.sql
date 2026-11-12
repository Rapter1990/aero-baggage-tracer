--liquibase formatted sql

--changeset identity:004-signing-key
-- Signing keys. Several can be live at once: the newest activated key signs, and replaced keys stay
-- published until the last token they signed has expired (retire_at). The private key is stored
-- AES-GCM encrypted (never in plain text) and is erased once the key is retired.
--   public_key      X.509 SubjectPublicKeyInfo, base64
--   private_key_enc base64(nonce || ciphertext), PKCS#8 encrypted with the key-encryption key
--   activate_at     when the key starts signing (it is published from created_at, earlier)
--   retire_at       when the key stops being published; NULL while it is the newest key
CREATE TABLE identity.signing_key (
    kid             varchar(64) PRIMARY KEY,
    algorithm       varchar(5)  NOT NULL,
    public_key      text        NOT NULL,
    private_key_enc text,
    created_at      timestamptz NOT NULL,
    activate_at     timestamptz NOT NULL,
    retire_at       timestamptz,
    CONSTRAINT ck_signing_key_algorithm CHECK (algorithm IN ('RS256', 'ES256'))
);

CREATE INDEX ix_signing_key_activate_at ON identity.signing_key (activate_at);
--rollback DROP TABLE identity.signing_key;
