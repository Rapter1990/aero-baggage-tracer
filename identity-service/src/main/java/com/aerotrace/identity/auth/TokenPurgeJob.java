package com.aerotrace.identity.auth;

import com.aerotrace.identity.constant.AuthConstants;
import com.aerotrace.identity.repository.PasswordResetTokenRepository;
import com.aerotrace.identity.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes refresh and password reset tokens that expired a while ago, so the tables do not grow
 * without bound. Several instances can run it at once: the deletes are idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TokenPurgeJob {

  private final RefreshTokenRepository refreshTokens;
  private final PasswordResetTokenRepository resetTokens;
  private final Clock clock;

  @Scheduled(
      fixedDelayString =
          "${aerotrace.identity.auth.purge-interval:" + AuthConstants.DEFAULT_PURGE_INTERVAL + "}",
      initialDelayString =
          "${aerotrace.identity.auth.purge-interval:" + AuthConstants.DEFAULT_PURGE_INTERVAL + "}")
  void purge() {
    Instant cutoff = clock.instant().minus(AuthConstants.TOKEN_RETENTION);
    int refresh = refreshTokens.deleteExpiredBefore(cutoff);
    int reset = resetTokens.deleteExpiredBefore(cutoff);
    if (refresh + reset > 0) {
      log.info("Purged {} refresh tokens and {} password reset tokens", refresh, reset);
    }
  }
}
