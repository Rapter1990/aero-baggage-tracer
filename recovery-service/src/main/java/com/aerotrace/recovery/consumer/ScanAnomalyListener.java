package com.aerotrace.recovery.consumer;

import com.aerotrace.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code baggage.scan.anomaly.v1} (key: bagTag). Failures are retried and finally
 * dead-lettered by the error handler in {@link KafkaConsumerConfiguration}.
 */
@Component
class ScanAnomalyListener {

  private final ScanAnomalyHandler handler;

  ScanAnomalyListener(ScanAnomalyHandler handler) {
    this.handler = handler;
  }

  @KafkaListener(
      id = "recovery-scan-anomaly",
      topics = Topics.SCAN_ANOMALY,
      autoStartup = "${aerotrace.recovery.consumers.enabled:true}")
  void onScanAnomaly(String event) {
    handler.handle(event);
  }
}
