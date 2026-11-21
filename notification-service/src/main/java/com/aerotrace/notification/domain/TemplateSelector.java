package com.aerotrace.notification.domain;

import com.aerotrace.common.model.RecoveryState;
import org.springframework.stereotype.Component;

/**
 * The template of a notification: one per case state (ADR 0005, decision 2). Different states never
 * share a template, so two changes of one case can never dedupe each other.
 */
@Component
public class TemplateSelector {

  /**
   * The template of a state the service does not know: a constant a newer producer added reads as
   * {@code null}. It still notifies; it is counted, not dead-lettered.
   */
  public static final String CASE_UPDATED = "CASE_UPDATED";

  public String select(RecoveryState state) {
    if (state == null) {
      return CASE_UPDATED;
    }
    return switch (state) {
      case OPEN -> "CASE_OPENED";
      case ASSESSING -> "CASE_ASSESSING";
      case RESERVED -> "CASE_RESERVED";
      case IN_TRANSIT -> "CASE_IN_TRANSIT";
      case RESOLVED -> "CASE_RESOLVED";
      case CLOSED_AS_UNRECOVERABLE -> "CASE_CLOSED_AS_UNRECOVERABLE";
      case CANCELLED -> "CASE_CANCELLED";
    };
  }
}
