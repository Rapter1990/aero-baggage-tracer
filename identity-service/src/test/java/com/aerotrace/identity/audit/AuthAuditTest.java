package com.aerotrace.identity.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Authentication events are recorded append-only, without any secret. */
class AuthAuditTest extends AbstractIntegrationTest {

  @Autowired private AuthAuditLog audit;
  @Autowired private JdbcClient jdbc;

  @Test
  void recordsAnEventWithItsOutcome() {
    String username = "audited-" + UUID.randomUUID().toString().substring(0, 8);

    audit.record(AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, username, null);

    Map<String, Object> row =
        jdbc.sql("select * from identity.auth_audit where username = :u")
            .param("u", username)
            .query()
            .singleRow();
    assertThat(row)
        .containsEntry("event_type", "LOGIN_FAILED")
        .containsEntry("outcome", "FAILURE")
        .containsKey("occurred_at");
  }

  @Test
  void aUsernameLongerThanTheColumnIsTruncatedNotRejected() {
    String longName = "x".repeat(500);

    audit.record(AuthEventType.LOGIN_FAILED, AuthOutcome.FAILURE, longName, null);

    int length =
        jdbc.sql(
                "select max(length(username)) from identity.auth_audit "
                    + "where username like 'xxxxxxxx%'")
            .query(Integer.class)
            .single();
    assertThat(length).isLessThanOrEqualTo(128);
  }

  @Test
  void anAuditFailureNeverBreaksTheCaller() {
    // A null event type violates the table's constraints; recording must swallow and log it.
    audit.record(null, AuthOutcome.FAILURE, "nobody", null);
  }
}
