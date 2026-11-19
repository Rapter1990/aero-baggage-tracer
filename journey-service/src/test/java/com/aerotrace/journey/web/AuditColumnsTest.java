package com.aerotrace.journey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Data auditing on the bag and its itinerary: who created them and when, and who last
 * changed the bag (a scan moves its latest state), taken from the caller's token.
 */
class AuditColumnsTest extends AbstractApiTest {

  @Test
  @DisplayName("a registered bag and its itinerary record who registered them")
  void registrationRecordsTheCreator() throws Exception {
    String bagTag = newBagTag();

    registerBag(registration(bagTag, newPassengerRef()), as(Role.ADMIN))
        .andExpect(status().isCreated());

    assertThat(text("select created_by from journey.baggage where bag_tag = ?", bagTag))
        .isEqualTo("user-admin");
    assertThat(text("select updated_by from journey.baggage where bag_tag = ?", bagTag))
        .isEqualTo("user-admin");
    assertThat(
            text(
                "select i.created_by from journey.itinerary i join journey.baggage b"
                    + " on b.id = i.baggage_id where b.bag_tag = ?",
                bagTag))
        .isEqualTo("user-admin");
  }

  @Test
  @DisplayName("a scan that moves the latest state records who changed the bag, and when")
  void scanRecordsTheLastModifier() throws Exception {
    String bagTag = newBagTag();
    registerBag(registration(bagTag, newPassengerRef()), as(Role.ADMIN))
        .andExpect(status().isCreated());
    Instant createdAt = instant("select created_at from journey.baggage where bag_tag = ?", bagTag);

    scan(barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(1)), newKey(), as(Role.AGENT))
        .andExpect(status().isAccepted());

    assertThat(text("select created_by from journey.baggage where bag_tag = ?", bagTag))
        .isEqualTo("user-admin");
    assertThat(text("select updated_by from journey.baggage where bag_tag = ?", bagTag))
        .isEqualTo("user-agent");
    assertThat(instant("select updated_at from journey.baggage where bag_tag = ?", bagTag))
        .isAfter(createdAt);
    assertThat(instant("select created_at from journey.baggage where bag_tag = ?", bagTag))
        .isEqualTo(createdAt);
  }

  private String text(String sql, Object... params) {
    return (String) single(sql, params);
  }

  private Instant instant(String sql, Object... params) {
    Object value = single(sql, params);
    return value instanceof Timestamp timestamp ? timestamp.toInstant() : (Instant) value;
  }

  private Object single(String sql, Object... params) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              var query = entityManager.createNativeQuery(sql.replace("?", "?1"));
              for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
              }
              return query.getSingleResult();
            });
  }
}
