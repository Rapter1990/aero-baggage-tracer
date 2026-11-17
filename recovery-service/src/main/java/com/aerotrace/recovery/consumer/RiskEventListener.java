package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code baggage.risk.changed.v1}. The key is {@code bagTag:legId}, so the changes of one leg
 * arrive in order on one partition. Failures are retried and finally dead-lettered by the error
 * handler in {@link KafkaConsumerConfiguration}; the payload is never logged.
 */
@Component
class RiskEventListener {

  private final RiskEventHandler handler;

  RiskEventListener(RiskEventHandler handler) {
    this.handler = handler;
  }

  @KafkaListener(
      id = "recovery-risk-changed",
      topics = Topics.RISK_CHANGED,
      autoStartup = "${aerotrace.recovery.consumers.enabled:true}")
  void onRiskChanged(String event) {
    handler.handle(event);
  }
}
