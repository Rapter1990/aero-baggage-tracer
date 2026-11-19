package com.aerotrace.search.indexer;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.search.constant.SearchTopics;
import com.aerotrace.search.elastic.IndexAdmin;
import com.aerotrace.search.elastic.IndexFamily;
import com.aerotrace.search.indexer.ReadModelWriter.CaseWrite;
import com.aerotrace.search.indexer.ReadModelWriter.ItemResult;
import com.aerotrace.search.indexer.ReadModelWriter.RiskHour;
import com.aerotrace.search.indexer.model.BagRegistered;
import com.aerotrace.search.indexer.model.CaseChange;
import com.aerotrace.search.indexer.model.LegSnapshot;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Indexes one batch of events of a topic. A batch is read, its poison events are dead-lettered, the
 * rest goes to Elasticsearch in bulk, and every item's answer is checked. The method returns only
 * when Elasticsearch has acknowledged every event (or the event was dead-lettered); the listener
 * container commits the batch's offsets after that. A failure that may pass (Elasticsearch down or
 * overloaded) is thrown, so the batch is retried and nothing is committed.
 *
 * <p>Each event's {@code correlationId} is put into the logging context while it is handled; none
 * is ever generated.
 */
@Component
public class EventIndexer {

  private static final Logger LOG = LoggerFactory.getLogger(EventIndexer.class);

  private final ReadModelWriter writer;
  private final IndexAdmin admin;
  private final DeadLetterPublisher deadLetters;
  private final IndexMetrics metrics;

  public EventIndexer(
      ReadModelWriter writer,
      IndexAdmin admin,
      DeadLetterPublisher deadLetters,
      IndexMetrics metrics) {
    this.writer = writer;
    this.admin = admin;
    this.deadLetters = deadLetters;
    this.metrics = metrics;
  }

  /** A readable event of the batch and the record it came from. */
  private record Read<T>(ConsumerRecord<String, String> record, EventEnvelope<T> event) {}

  private <T> List<Read<T>> read(
      List<ConsumerRecord<String, String>> records,
      String eventType,
      Class<T> payloadType,
      Function<EventEnvelope<T>, EventEnvelope<T>> validate) {
    List<Read<T>> valid = new ArrayList<>();
    for (ConsumerRecord<String, String> record : records) {
      try {
        EventEnvelope<T> event =
            validate.apply(EventReader.read(record.value(), eventType, payloadType));
        valid.add(new Read<>(record, event));
      } catch (PoisonEventException poison) {
        deadLetters.publish(record, poison.getMessage());
        metrics.count(record.topic(), Outcome.FAILED);
      }
    }
    return valid;
  }

  private <T> void settle(List<Read<T>> events, List<ItemResult> results) {
    for (int i = 0; i < events.size(); i++) {
      Read<T> read = events.get(i);
      ItemResult result = results.get(i);
      try (CorrelationScope ignored = CorrelationScope.of(read.event().correlationId())) {
        if (result.rejected()) {
          // Refused for good (for example by the strict mapping): retrying cannot help.
          deadLetters.publish(read.record(), "refused by the index: " + result.rejection());
          metrics.count(read.record().topic(), Outcome.FAILED);
        } else {
          metrics.count(read.record().topic(), result.outcome());
          metrics.acknowledged(read.event().publishedAt());
          LOG.debug(
              "Indexed {} {}: {}",
              read.event().eventType(),
              read.event().aggregateId(),
              result.outcome().tag());
        }
      }
    }
  }

  // ------------------------------------------------------------------ cases

  public void indexCases(List<ConsumerRecord<String, String>> records) throws IOException {
    List<Read<CaseChange>> events =
        read(
            records,
            SearchTopics.EVENT_TYPE_CASE_CHANGED,
            CaseChange.class,
            EventIndexer::validCase);
    if (events.isEmpty()) {
      return;
    }
    List<CaseWrite> writes =
        events.stream()
            .map(r -> new CaseWrite(r.event().payload(), r.event().occurredAt()))
            .toList();
    List<ItemResult> results = writer.writeCases(writes, admin.liveIndexes(IndexFamily.CASES));
    List<JourneyChange> journeys = new ArrayList<>();
    for (Read<CaseChange> read : events) {
      journeys.add(caseOnJourney(read.event().payload(), read.event().occurredAt()));
    }
    writer.applyJourneys(journeys, admin.liveIndexes(IndexFamily.JOURNEYS));
    settle(events, results);
  }

  public static JourneyChange caseOnJourney(CaseChange c, Instant eventAt) {
    Instant caseAt = c.openedAt() != null ? c.openedAt() : eventAt;
    return JourneyChange.caseState(
        c.bagTag(),
        c.legId(),
        c.caseId(),
        c.state().name(),
        c.version(),
        caseAt != null ? caseAt : Instant.EPOCH,
        eventAt);
  }

  private static EventEnvelope<CaseChange> validCase(EventEnvelope<CaseChange> event) {
    CaseChange c = event.payload();
    EventReader.required(c.caseId(), "caseId");
    EventReader.required(c.bagTag(), "bagTag");
    EventReader.required(c.legId(), "legId");
    if (c.state() == null) {
      throw new PoisonEventException("state is missing or unknown");
    }
    if (c.version() < 0) {
      throw new PoisonEventException("version is negative");
    }
    return event;
  }

  // ------------------------------------------------------------------ scans

  public void indexScans(List<ConsumerRecord<String, String>> records) throws IOException {
    List<Read<ScanReceivedPayload>> events =
        read(
            records,
            SearchTopics.EVENT_TYPE_SCAN_RECEIVED,
            ScanReceivedPayload.class,
            EventIndexer::validScan);
    if (events.isEmpty()) {
      return;
    }
    List<JourneyChange> changes = new ArrayList<>();
    for (Read<ScanReceivedPayload> read : events) {
      ScanReceivedPayload s = read.event().payload();
      changes.add(
          JourneyChange.scan(
              s.bagTag(),
              s.airport(),
              s.checkpoint(),
              s.deviceSequence(),
              s.occurredAt(),
              s.legId(),
              s.flightNumber(),
              s.departureAt()));
    }
    settle(events, writer.applyJourneys(changes, admin.liveIndexes(IndexFamily.JOURNEYS)));
  }

  private static EventEnvelope<ScanReceivedPayload> validScan(
      EventEnvelope<ScanReceivedPayload> event) {
    ScanReceivedPayload s = event.payload();
    EventReader.required(s.bagTag(), "bagTag");
    EventReader.required(s.airport(), "airport");
    if (s.occurredAt() == null) {
      throw new PoisonEventException("occurredAt is missing");
    }
    return event;
  }

  // ------------------------------------------------------------------ risk

  public void indexRisks(List<ConsumerRecord<String, String>> records) throws IOException {
    List<Read<RiskChangedPayload>> events =
        read(
            records,
            SearchTopics.EVENT_TYPE_RISK_CHANGED,
            RiskChangedPayload.class,
            EventIndexer::validRisk);
    if (events.isEmpty()) {
      return;
    }
    settle(
        events,
        applyRisks(
            events.stream().map(Read::event).toList(), admin.liveIndexes(IndexFamily.JOURNEYS)));
  }

  /** Applies risk events to the given journey indexes; also used by the rebuild's replay. */
  public List<ItemResult> applyRisks(
      List<EventEnvelope<RiskChangedPayload>> events, List<String> journeyIndexes)
      throws IOException {
    List<JourneyChange> changes = new ArrayList<>();
    List<RiskHour> hours = new ArrayList<>();
    for (EventEnvelope<RiskChangedPayload> event : events) {
      RiskChangedPayload r = event.payload();
      changes.add(
          JourneyChange.risk(
              r.bagTag(), r.legId(), r.current().name(), event.occurredAt(), r.departureAt()));
      if (r.current() == RiskLevel.HIGH) {
        hours.add(new RiskHour(r.bagTag(), r.legId(), event.occurredAt()));
      }
    }
    writer.writeRiskHours(hours);
    return writer.applyJourneys(changes, journeyIndexes);
  }

  static EventEnvelope<RiskChangedPayload> validRisk(EventEnvelope<RiskChangedPayload> event) {
    RiskChangedPayload r = event.payload();
    EventReader.required(r.bagTag(), "bagTag");
    EventReader.required(r.legId(), "legId");
    if (r.current() == null) {
      throw new PoisonEventException("current risk level is missing or unknown");
    }
    return event;
  }

  // ------------------------------------------------------------------ bag registration

  public void indexRegistrations(List<ConsumerRecord<String, String>> records) throws IOException {
    List<Read<BagRegistered>> events =
        read(
            records,
            SearchTopics.EVENT_TYPE_BAG_REGISTERED,
            BagRegistered.class,
            EventIndexer::validRegistration);
    if (events.isEmpty()) {
      return;
    }
    Map<String, LegSnapshot> legs = new LinkedHashMap<>();
    Map<String, String> bagOfLeg = new LinkedHashMap<>();
    List<JourneyChange> changes = new ArrayList<>();
    for (Read<BagRegistered> read : events) {
      BagRegistered bag = read.event().payload();
      for (LegSnapshot leg : bag.legs()) {
        legs.put(leg.legId(), leg);
        bagOfLeg.put(leg.legId(), bag.bagTag());
      }
      changes.add(JourneyChange.itinerary(bag.bagTag(), bag.legs(), read.event().occurredAt()));
    }
    writer.writeLegs(legs, bagOfLeg);
    settle(events, writer.applyJourneys(changes, admin.liveIndexes(IndexFamily.JOURNEYS)));
  }

  private static EventEnvelope<BagRegistered> validRegistration(
      EventEnvelope<BagRegistered> event) {
    BagRegistered bag = event.payload();
    EventReader.required(bag.bagTag(), "bagTag");
    if (bag.legs() == null || bag.legs().isEmpty()) {
      throw new PoisonEventException("legs are missing");
    }
    for (LegSnapshot leg : bag.legs()) {
      if (leg == null) {
        throw new PoisonEventException("a leg is missing");
      }
      EventReader.required(leg.legId(), "legId");
    }
    return event;
  }
}
