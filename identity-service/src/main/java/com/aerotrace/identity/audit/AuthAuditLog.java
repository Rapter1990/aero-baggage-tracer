package com.aerotrace.identity.audit;

import com.aerotrace.identity.model.entity.AuthAuditEntity;
import com.aerotrace.identity.repository.AuthAuditRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the append-only audit trail of authentication events. It accepts only an event type, an
 * outcome and who it concerned: there is no way to pass a password, token or other secret in.
 *
 * <p>Each entry is written in a transaction of its own, so a failure to audit can never poison the
 * caller's transaction.
 */
@Component
public class AuthAuditLog {

  private static final Logger log = LoggerFactory.getLogger(AuthAuditLog.class);
  private static final int MAX_USERNAME = 128;

  private final AuthAuditRepository repository;
  private final Clock clock;
  private final TransactionTemplate separateTransaction;

  AuthAuditLog(AuthAuditRepository repository, Clock clock, PlatformTransactionManager manager) {
    this.repository = repository;
    this.clock = clock;
    this.separateTransaction = new TransactionTemplate(manager);
    this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
      log.warn("Could not write an authentication audit entry ({})", e.getClass().getSimpleName());
    }
  }

  private static String truncate(String username) {
    if (username == null || username.length() <= MAX_USERNAME) {
      return username;
    }
    return username.substring(0, MAX_USERNAME);
  }
}
