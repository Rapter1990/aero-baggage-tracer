package com.aerotrace.notification.consumer;

import com.aerotrace.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code recovery.case.changed.v1} in the group {@code notification-service} (ADR 0005,
 * decision 5). The key is the case id, so the changes of one case arrive in order. Failures are
 * retried and finally dead-lettered by the error handler in {@link KafkaConsumerConfiguration}; the
 * payload is never logged.
 */
@Component
class CaseChangedListener {

  static final String LISTENER_ID = "notification-case-changed";

  private final CaseChangedHandler handler;

  CaseChangedListener(CaseChangedHandler handler) {
    this.handler = handler;
  }

  @KafkaListener(
      id = LISTENER_ID,
      // The group is spring.kafka.consumer.group-id, not the listener id.
      idIsGroup = false,
      topics = Topics.CASE_CHANGED,
      autoStartup = "${aerotrace.notification.consumers.enabled:true}")
  void onCaseChanged(String event) {
    handler.handle(event);
  }
}
