package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code recovery.capacity.changed.v1} (key: resourceId, so the events of a resource arrive
 * in order). Failures are retried and finally dead-lettered by the error handler in {@link
 * KafkaConsumerConfiguration}.
 */
@Component
class CapacityEventListener {

  private final CapacityEventHandler handler;

  CapacityEventListener(CapacityEventHandler handler) {
    this.handler = handler;
  }

  @KafkaListener(
      id = "recovery-capacity-changed",
      topics = Topics.CAPACITY_CHANGED,
      autoStartup = "${aerotrace.recovery.consumers.enabled:true}")
  void onCapacityChanged(String event) {
    handler.handle(event);
  }
}
