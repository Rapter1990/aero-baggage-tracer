package com.aerotrace.identity.audit;

import com.aerotrace.identity.constant.AuditConstants;
import com.aerotrace.identity.model.entity.AuthAuditEntity;
import com.aerotrace.identity.repository.AuthAuditRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the append-only audit trail of authentication events. It accepts only an event type, an
 * outcome and who it concerned: there is no way to pass a password, token or other secret in.
 *
 * <p>Inside a caller's transaction the entry joins it, so the action and its audit entry commit or
 * roll back together and no second database connection is needed (a second connection per request
 * would starve the pool under load). Outside one, the entry gets a transaction of its own and a
 * failure to write it is logged, never thrown, so auditing cannot turn a sign-in into an error.
 */
@Slf4j
@Component
public class AuthAuditLog {

  private final AuthAuditRepository repository;
  private final Clock clock;
  private final TransactionTemplate separateTransaction;

  AuthAuditLog(AuthAuditRepository repository, Clock clock, PlatformTransactionManager manager) {
    this.repository = repository;
    this.clock = clock;
    this.separateTransaction = new TransactionTemplate(manager);
  }

  /**
   * Records an event. A failure to write the audit entry is logged (without any detail from the
   * event) and never thrown, so auditing cannot turn a sign-in into an error.
   */
  public void record(AuthEventType type, AuthOutcome outcome, String username, UUID userId) {
    try {
      separateTransaction.executeWithoutResult(
          status ->
              repository.save(
                  new AuthAuditEntity(
                      clock.instant(),
                      type == null ? null : type.name(),
                      outcome == null ? null : outcome.name(),
                      truncate(username),
                      userId)));
    } catch (RuntimeException e) {
      if (TransactionSynchronizationManager.isActualTransactionActive()) {
        // Joined the caller's transaction, which is now unusable: fail the request, not the audit.
        throw e;
      }
      log.warn("Could not write an authentication audit entry ({})", e.getClass().getSimpleName());
    }
  }

  private static String truncate(String username) {
    if (username == null || username.length() <= AuditConstants.MAX_USERNAME_LENGTH) {
      return username;
    }
    return username.substring(0, AuditConstants.MAX_USERNAME_LENGTH);
  }
}
