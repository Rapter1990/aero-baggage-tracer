package com.aerotrace.identity.audit;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes the append-only audit trail of authentication events. It accepts only an event type, an
 * outcome and who it concerned: there is no way to pass a password, token or other secret in.
 */
@Component
public class AuthAuditLog {

  private static final Logger log = LoggerFactory.getLogger(AuthAuditLog.class);
  private static final int MAX_USERNAME = 128;

  private final JdbcClient jdbc;

  AuthAuditLog(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records an event. A failure to write the audit entry is logged (without any detail from the
   * event) and never thrown, so auditing cannot turn a sign-in into an error.
   */
  public void record(AuthEventType type, AuthOutcome outcome, String username, UUID userId) {
    try {
      jdbc.sql(
              "INSERT INTO identity.auth_audit (event_type, outcome, username, user_id) "
                  + "VALUES (:type, :outcome, :username, :userId)")
          .param("type", type == null ? null : type.name())
          .param("outcome", outcome == null ? null : outcome.name())
          .param("username", truncate(username))
          .param("userId", userId)
          .update();
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
