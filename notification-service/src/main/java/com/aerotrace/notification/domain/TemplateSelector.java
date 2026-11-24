package com.aerotrace.notification.domain;

import com.aerotrace.common.model.RecoveryState;
import com.aerotrace.notification.constant.NotificationRules;
import org.springframework.stereotype.Component;

/**
 * The template of a notification: one per case state (ADR 0005, decision 2). Different states never
 * share a template, so two changes of one case can never dedupe each other.
 */
@Component
public class TemplateSelector {

  public String select(RecoveryState state) {
    if (state == null) {
      return NotificationRules.CASE_UPDATED;
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
