package com.aerotrace.notification.consumer;

import com.aerotrace.common.correlation.CorrelationId;
import com.aerotrace.common.correlation.CorrelationScope;
import com.aerotrace.common.event.CaseChangedPayload;
import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.notification.constant.MetricNames;
import com.aerotrace.notification.domain.RecipientResolver;
import com.aerotrace.notification.domain.TemplateSelector;
import com.aerotrace.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns one {@code recovery.case.changed.v1} event into at most one notification per recipient
 * (BR-08, AT-13; ADR 0005, decisions 1 to 8). The unique key {@code (recipient, eventId, template)}
 * is the only deduplication: a second copy of the event, or the same event on another replica,
 * inserts nothing and is a success. There is no ordering filter.
 *
 * <p>Logs carry the case id, event id and template only: never the recipient or the reason.
 */
@Component
public class CaseChangedHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CaseChangedHandler.class);

  /** What handling an event did. Every outcome is a success: the offset may be committed. */
  public enum Outcome {
    NOTIFICATION_CREATED,
    DUPLICATE,
    NO_RECIPIENT
  }

  private final NotificationRepository notifications;
  private final RecipientResolver recipients;
  private final TemplateSelector templates;
  private final MeterRegistry meters;

  public CaseChangedHandler(
      NotificationRepository notifications,
      RecipientResolver recipients,
      TemplateSelector templates,
      MeterRegistry meters) {
    this.notifications = notifications;
    this.recipients = recipients;
    this.templates = templates;
    this.meters = meters;
  }

  /**
   * Handles one raw event. A {@link PoisonEventException} means it can never be handled; any other
   * exception is transient and the caller retries.
   */
  public Outcome handle(String json) {
    EventEnvelope<CaseChangedPayload> event = EventReader.read(json);
    try (CorrelationScope ignored = CorrelationScope.of(event.correlationId())) {
      return process(event);
    }
  }

  private Outcome process(EventEnvelope<CaseChangedPayload> event) {
    CaseChangedPayload payload = event.payload();
    UUID caseId = EventReader.caseId(payload);
    Optional<String> recipient = recipients.resolve(payload);
    if (recipient.isEmpty()) {
      LOG.debug("No recipient for case {} event {}", caseId, event.eventId());
      count(MetricNames.UNASSIGNED);
      return Outcome.NO_RECIPIENT;
    }
    String template = templates.select(payload.state());
    String correlationId =
        CorrelationId.isValid(event.correlationId()) ? event.correlationId() : null;
    int inserted =
        notifications.insertIfAbsent(
            UUID.randomUUID(),
            recipient.get(),
            event.eventId(),
            template,
            caseId,
            payload.bagTag(),
            payload.state() == null ? null : payload.state().name(),
            payload.version(),
            event.occurredAt(),
            correlationId);
    if (inserted == 0) {
      LOG.debug(
          "Duplicate case event {} for case {} template {}", event.eventId(), caseId, template);
      count(MetricNames.DUPLICATE);
      return Outcome.DUPLICATE;
    }
    if (payload.state() == null) {
      LOG.warn(
          "Case {} event {} has a state this service does not know; notified with {}",
          caseId,
          event.eventId(),
          template);
      count(MetricNames.UNKNOWN_STATE);
    } else {
      count(MetricNames.CREATED);
    }
    LOG.info(
        "Notification created for case {} event {} template {}", caseId, event.eventId(), template);
    return Outcome.NOTIFICATION_CREATED;
  }

  private void count(String outcome) {
    meters.counter(MetricNames.EVENTS, MetricNames.OUTCOME, outcome).increment();
  }
}
