package com.aerotrace.identity.repository;

import com.aerotrace.identity.model.entity.RefreshTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Access to refresh tokens, by the hash of the token. */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  @Query("select t from RefreshTokenEntity t join fetch t.user where t.tokenHash = :hash")
  Optional<RefreshTokenEntity> findByTokenHash(@Param("hash") String hash);

  /**
   * Revokes one token if it is still active. Returns 1 for the caller that revoked it and 0 for
   * anyone who tries to use it after (a replay, or a second request racing the first).
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RefreshTokenEntity t set t.revokedAt = :now, t.revokedReason = :reason "
          + "where t.id = :id and t.revokedAt is null")
  int revoke(@Param("id") UUID id, @Param("now") Instant now, @Param("reason") String reason);

  /** Revokes every token of a user that is still active; returns how many. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update RefreshTokenEntity t set t.revokedAt = :now, t.revokedReason = :reason "
          + "where t.user.id = :userId and t.revokedAt is null")
  int revokeAllActiveForUser(
      @Param("userId") UUID userId, @Param("now") Instant now, @Param("reason") String reason);

  /** Deletes tokens that expired before the cutoff; returns how many. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from RefreshTokenEntity t where t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
