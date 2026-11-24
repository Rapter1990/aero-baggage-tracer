package com.aerotrace.notification.domain;

import com.aerotrace.common.event.CaseChangedPayload;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Who a case change notifies: the case's assigned handler, the identity user id in {@code
 * assignedTo} (an opaque token subject, not personal data). Nothing else is ever used to guess a
 * recipient. A revision 1 event or an unassigned case has none (ADR 0005, decision 1).
 */
@Component
public class RecipientResolver {

  public Optional<String> resolve(CaseChangedPayload payload) {
    String assignedTo = payload.assignedTo();
    if (assignedTo == null || assignedTo.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(assignedTo);
  }
}
