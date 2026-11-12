package com.aerotrace.identity.repository;

import com.aerotrace.identity.model.entity.PasswordResetTokenEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Access to password reset tokens, by the hash of the token. */
public interface PasswordResetTokenRepository
    extends JpaRepository<PasswordResetTokenEntity, UUID> {

  @Query("select t from PasswordResetTokenEntity t join fetch t.user where t.tokenHash = :hash")
  Optional<PasswordResetTokenEntity> findByTokenHash(@Param("hash") String hash);

  /** Marks a token used if it still is not; returns 0 for a second use of the same token. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update PasswordResetTokenEntity t set t.usedAt = :now where t.id = :id and t.usedAt is null")
  int markUsed(@Param("id") UUID id, @Param("now") Instant now);

  /** A new request replaces earlier ones: every unused token of the user is used up. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update PasswordResetTokenEntity t set t.usedAt = :now "
          + "where t.user.id = :userId and t.usedAt is null")
  int invalidateAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

  /** Deletes tokens that expired before the cutoff; returns how many. */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("delete from PasswordResetTokenEntity t where t.expiresAt < :cutoff")
  int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
