package com.aerotrace.identity.keys;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistence of signing keys. Every time-dependent query takes {@code now} from the caller. */
@Repository
class SigningKeyRepository {

  /** Arbitrary constant naming the advisory lock that serializes key changes across replicas. */
  private static final long LOCK_ID = 7_102_001L;

  record StoredKey(
      String kid,
      String algorithm,
      String publicKey,
      String privateKeyEnc,
      Instant createdAt,
      Instant activateAt,
      Instant retireAt) {

    @Override
    public String toString() {
      return "StoredKey[kid=" + kid + ", algorithm=" + algorithm + "]";
    }
  }

  private static final String COLUMNS =
      "kid, algorithm, public_key, private_key_enc, created_at, activate_at, retire_at";

  private final JdbcClient jdbc;

  SigningKeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Held until the surrounding transaction ends. */
  void lock() {
    jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:id)) l")
        .param("id", LOCK_ID)
        .query(Integer.class)
        .list();
  }

  void insert(StoredKey key) {
    jdbc.sql(
            "INSERT INTO identity.signing_key ("
                + COLUMNS
                + ") VALUES (:kid, :alg, :pub, :priv, :created, :activate, NULL)")
        .param("kid", key.kid())
        .param("alg", key.algorithm())
        .param("pub", key.publicKey())
        .param("priv", key.privateKeyEnc())
        .param("created", Timestamp.from(key.createdAt()))
        .param("activate", Timestamp.from(key.activateAt()))
        .update();
  }

  /** Keys that are still published (not retired), including ones that have not started signing. */
  List<StoredKey> findPublished(Instant now) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM identity.signing_key WHERE retire_at IS NULL OR retire_at > :now "
                + "ORDER BY activate_at")
        .param("now", Timestamp.from(now))
        .query(SigningKeyRepository::map)
        .list();
  }

  /** The key that signs now: the most recently activated one that is not retired. */
  Optional<StoredKey> findSigning(Instant now) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM identity.signing_key WHERE activate_at <= :now "
                + "AND (retire_at IS NULL OR retire_at > :now) AND private_key_enc IS NOT NULL "
                + "ORDER BY activate_at DESC LIMIT 1")
        .param("now", Timestamp.from(now))
        .query(SigningKeyRepository::map)
        .optional();
  }

  int countPublished(Instant now) {
    return jdbc.sql(
            "SELECT count(*) FROM identity.signing_key WHERE retire_at IS NULL OR retire_at > :now")
        .param("now", Timestamp.from(now))
        .query(Integer.class)
        .single();
  }

  /** A key that has been published but has not started signing yet. */
  Optional<StoredKey> findPending(Instant now) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM identity.signing_key WHERE activate_at > :now "
                + "ORDER BY activate_at DESC LIMIT 1")
        .param("now", Timestamp.from(now))
        .query(SigningKeyRepository::map)
        .optional();
  }

  Optional<Instant> newestActivation() {
    return jdbc.sql("SELECT max(activate_at) FROM identity.signing_key")
        .query((rs, row) -> Optional.ofNullable(rs.getTimestamp(1)).map(Timestamp::toInstant))
        .single();
  }

  /** Marks every key that has no retirement yet, except the new one, to retire at that time. */
  void scheduleRetirement(Instant retireAt, String exceptKid) {
    jdbc.sql(
            "UPDATE identity.signing_key SET retire_at = :retire "
                + "WHERE retire_at IS NULL AND kid <> :kid")
        .param("retire", Timestamp.from(retireAt))
        .param("kid", exceptKid)
        .update();
  }

  /** Erases the private key of every retired key. Returns how many were erased. */
  int eraseRetiredPrivateKeys(Instant now) {
    return jdbc.sql(
            "UPDATE identity.signing_key SET private_key_enc = NULL "
                + "WHERE retire_at <= :now AND private_key_enc IS NOT NULL")
        .param("now", Timestamp.from(now))
        .update();
  }

  private static StoredKey map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    Timestamp retire = rs.getTimestamp("retire_at");
    return new StoredKey(
        rs.getString("kid"),
        rs.getString("algorithm"),
        rs.getString("public_key"),
        rs.getString("private_key_enc"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("activate_at").toInstant(),
        retire == null ? null : retire.toInstant());
  }
}
