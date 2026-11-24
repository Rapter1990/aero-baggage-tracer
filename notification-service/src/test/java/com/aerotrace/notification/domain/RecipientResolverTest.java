package com.aerotrace.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.model.CaseTrigger;
import com.aerotrace.common.model.RecoveryState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecipientResolverTest {

  private final RecipientResolver resolver = new RecipientResolver();

  private static CaseChangedPayload payload(String assignedTo) {
    Instant at = Instant.parse("2026-03-01T09:00:00Z");
    return new CaseChangedPayload(
        "case-1",
        "0123456789",
        "leg-1",
        RecoveryState.RESERVED,
        3,
        "Maria Gonzalez asked for help at gate B12",
        at,
        null,
        CaseTrigger.RISK,
        assignedTo);
  }

  @Test
  @DisplayName("BR-08 the recipient is the case's assigned handler")
  void recipientIsTheAssignedHandler() {
    assertThat(resolver.resolve(payload("handler-42"))).contains("handler-42");
  }

  @Test
  @DisplayName("BR-08 a case without a handler has no recipient (revision 1 or unassigned)")
  void noHandlerNoRecipient() {
    assertThat(resolver.resolve(payload(null))).isEmpty();
  }

  @Test
  @DisplayName("BR-08 a blank handler is no recipient either")
  void blankHandlerIsNoRecipient() {
    assertThat(resolver.resolve(payload(""))).isEmpty();
    assertThat(resolver.resolve(payload("   "))).isEmpty();
  }

  @Test
  @DisplayName("BR-08 the recipient is never guessed from the bag tag, the leg or the reason")
  void recipientIsNeverGuessed() {
    // The payload has a bag tag, a leg and a reason, but no handler: there is no recipient at all.
    assertThat(resolver.resolve(payload(null))).isEmpty();
    assertThat(resolver.resolve(payload("handler-42"))).hasValue("handler-42");
  }
}
