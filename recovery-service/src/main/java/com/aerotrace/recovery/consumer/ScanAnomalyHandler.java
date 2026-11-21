package com.aerotrace.recovery.consumer;

import com.aerotrace.common.correlation.CorrelationScope;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.ScanAnomalyPayload;
import com.aerotrace.common.model.AnomalyType;
import com.aerotrace.recovery.constant.RecoveryRules;
import com.aerotrace.recovery.repository.ProcessedEventRepository;
import com.aerotrace.recovery.service.RecoveryCaseService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles {@code baggage.scan.anomaly.v1}. A SCANNER_ANOMALY (BR-03: three rejected transitions in
 * a five-minute event-time window) means the scans of this bag cannot be trusted, so every active
 * case of the bag is flagged and planned for the service desk. A bag without an active case is left
 * alone: an anomaly on its own is no missed connection. Other anomaly types (LATE_EVENT is only an
 * audit flag, and a newer producer may add types) are counted in a metric and skipped.
 *
 * <p>As for risk events, the event id is recorded in the same transaction as the effect.
 */
@Component
public class ScanAnomalyHandler {

  /** What an event led to. */
  public enum Outcome {
    CASES_FLAGGED,
    NO_CASE_TO_FLAG,
    IGNORED_TYPE,
    DUPLICATE
  }

  private static final Pattern BAG_TAG = Pattern.compile(RecoveryRules.BAG_TAG_PATTERN);

  private final RecoveryCaseService cases;
  private final ProcessedEventRepository processed;
  private final Clock clock;
  private final MeterRegistry meters;
  private final TransactionTemplate transaction;

  public ScanAnomalyHandler(
      RecoveryCaseService cases,
      ProcessedEventRepository processed,
      Clock clock,
      MeterRegistry meters,
      PlatformTransactionManager transactionManager) {
    this.cases = cases;
    this.processed = processed;
    this.clock = clock;
    this.meters = meters;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public Outcome handle(String json) {
    EventEnvelope<ScanAnomalyPayload> event =
        EventReader.read(json, RecoveryRules.EVENT_TYPE_SCAN_ANOMALY, ScanAnomalyPayload.class);
    ScanAnomalyPayload anomaly = event.payload();
    if (anomaly.bagTag() == null || !BAG_TAG.matcher(anomaly.bagTag()).matches()) {
      throw new PoisonEventException("bagTag is not a bag tag");
    }
    // The case events written while handling it carry the anomaly event's correlation id.
    try (CorrelationScope ignored = CorrelationScope.of(event.correlationId())) {
      Outcome outcome = transaction.execute(status -> process(event));
      meters.counter("recovery.anomaly.events", "outcome", outcome.name()).increment();
      return outcome;
    }
  }

  private Outcome process(EventEnvelope<ScanAnomalyPayload> event) {
    if (!processed.markProcessed(EventReader.dedupeKey(event), Instant.now(clock))) {
      return Outcome.DUPLICATE;
    }
    ScanAnomalyPayload anomaly = event.payload();
    if (anomaly.anomalyType() != AnomalyType.SCANNER_ANOMALY) {
      return Outcome.IGNORED_TYPE;
    }
    return cases.flagScannerAnomaly(anomaly.bagTag()) > 0
        ? Outcome.CASES_FLAGGED
        : Outcome.NO_CASE_TO_FLAG;
  }
}
