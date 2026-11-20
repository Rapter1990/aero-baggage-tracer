package com.aerotrace.search.indexer;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.RiskChangedPayload;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.model.RiskLevel;
import com.aerotrace.search.constant.SearchRules;
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
 * Indexes one batch of events of a topic. A batch is read, its valid events go to Elasticsearch in
 * bulk and every item's answer is checked; only then are its poison events and the events
 * Elasticsearch refused for good dead-lettered (once per batch, not once per retry). The method
 * returns only when Elasticsearch has acknowledged every event (or the event was dead-lettered);
 * the listener container commits the batch's offsets after that. A failure that may pass
 * (Elasticsearch down or overloaded) is thrown before anything is dead-lettered, so the batch is
 * retried and nothing is committed or published.
 *
 * <p>Each event's {@code correlationId} is put into the logging context while it is handled; none
 * is ever generated.
 */
@Component
public class EventIndexer {

  private static final Logger LOG = LoggerFactory.getLogger(EventIndexer.class);
  private static final int MAX_LEGS = 32;

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

  /** A record that can never be indexed, and why. */
  private record Poison(ConsumerRecord<String, String> record, String reason) {}

  /**
   * A read batch: its valid events, and its poison records, which are dead-lettered only once the
   * write of the valid events has succeeded. Were they dead-lettered first, a batch retried while
   * Elasticsearch is down would publish the same dead letters again on every attempt.
   */
  private record Batch<T>(List<Read<T>> events, List<Poison> poison) {}

  private <T> Batch<T> read(
      List<ConsumerRecord<String, String>> records,
      String eventType,
      Class<T> payloadType,
      Function<EventEnvelope<T>, EventEnvelope<T>> validate) {
    List<Read<T>> valid = new ArrayList<>();
    List<Poison> poison = new ArrayList<>();
    for (ConsumerRecord<String, String> record : records) {
      try {
        EventEnvelope<T> event =
            validate.apply(EventReader.read(record.value(), eventType, payloadType));
        valid.add(new Read<>(record, event));
      } catch (PoisonEventException e) {
        poison.add(new Poison(record, e.getMessage()));
      }
    }
    return new Batch<>(valid, poison);
  }

  /**
   * Called once the batch was written (or had nothing to write): dead-letters its poison records
   * and the events Elasticsearch refused for good, and counts the rest.
   */
  private <T> void settle(Batch<T> batch, List<ItemResult> results) {
    for (Poison poison : batch.poison()) {
      deadLetters.publish(poison.record(), poison.reason());
      metrics.count(poison.record().topic(), Outcome.FAILED);
    }
    List<Read<T>> events = batch.events();
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
    Batch<CaseChange> batch =
        read(
            records,
            SearchTopics.EVENT_TYPE_CASE_CHANGED,
            CaseChange.class,
            EventIndexer::validCase);
    List<Read<CaseChange>> events = batch.events();
    if (events.isEmpty()) {
      settle(batch, List.of());
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
    // A case event writes two documents; a refusal of either dead-letters the event.
    settle(
        batch,
        ItemResult.merge(
            results, writer.applyJourneys(journeys, admin.liveIndexes(IndexFamily.JOURNEYS))));
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
    Batch<ScanReceivedPayload> batch =
        read(
            records,
            SearchTopics.EVENT_TYPE_SCAN_RECEIVED,
            ScanReceivedPayload.class,
            EventIndexer::validScan);
    List<Read<ScanReceivedPayload>> events = batch.events();
    if (events.isEmpty()) {
      settle(batch, List.of());
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
    settle(batch, writer.applyJourneys(changes, admin.liveIndexes(IndexFamily.JOURNEYS)));
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
    Batch<RiskChangedPayload> batch =
        read(
            records,
            SearchTopics.EVENT_TYPE_RISK_CHANGED,
            RiskChangedPayload.class,
            EventIndexer::validRisk);
    List<Read<RiskChangedPayload>> events = batch.events();
    if (events.isEmpty()) {
      settle(batch, List.of());
      return;
    }
    settle(
        batch,
        applyRisks(
            events.stream().map(Read::event).toList(), admin.liveIndexes(IndexFamily.JOURNEYS)));
  }

  /**
   * Applies risk events to the given journey indexes and records their HIGH risk hours; also used
   * by the rebuild's replay. One result per event: refused when its journey or its risk hour was.
   */
  public List<ItemResult> applyRisks(
      List<EventEnvelope<RiskChangedPayload>> events, List<String> journeyIndexes)
      throws IOException {
    List<JourneyChange> changes = new ArrayList<>();
    List<RiskHour> hours = new ArrayList<>();
    int[] hourOf = new int[events.size()];
    for (int i = 0; i < events.size(); i++) {
      EventEnvelope<RiskChangedPayload> event = events.get(i);
      RiskChangedPayload r = event.payload();
      changes.add(
          JourneyChange.risk(
              r.bagTag(), r.legId(), r.current().name(), event.occurredAt(), r.departureAt()));
      hourOf[i] = -1;
      if (r.current() == RiskLevel.HIGH) {
        hourOf[i] = hours.size();
        hours.add(new RiskHour(r.bagTag(), r.legId(), event.occurredAt()));
      }
    }
    List<ItemResult> hourResults = writer.writeRiskHours(hours);
    List<ItemResult> journeys = writer.applyJourneys(changes, journeyIndexes);
    List<ItemResult> results = new ArrayList<>(events.size());
    for (int i = 0; i < events.size(); i++) {
      results.add(
          ItemResult.merge(journeys.get(i), hourOf[i] < 0 ? null : hourResults.get(hourOf[i])));
    }
    return results;
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
    Batch<BagRegistered> batch =
        read(
            records,
            SearchTopics.EVENT_TYPE_BAG_REGISTERED,
            BagRegistered.class,
            EventIndexer::validRegistration);
    List<Read<BagRegistered>> events = batch.events();
    if (events.isEmpty()) {
      settle(batch, List.of());
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
    Map<String, String> refusedLegs = writer.writeLegs(legs, bagOfLeg);
    List<ItemResult> journeys =
        writer.applyJourneys(changes, admin.liveIndexes(IndexFamily.JOURNEYS));
    List<ItemResult> results = new ArrayList<>(events.size());
    for (int i = 0; i < events.size(); i++) {
      ItemResult legResult = null;
      for (LegSnapshot leg : events.get(i).event().payload().legs()) {
        String refused = refusedLegs.get(leg.legId());
        if (refused != null) {
          legResult = ItemResult.rejected("leg " + refused);
          break;
        }
      }
      results.add(ItemResult.merge(journeys.get(i), legResult));
    }
    settle(batch, results);
  }

  private static EventEnvelope<BagRegistered> validRegistration(
      EventEnvelope<BagRegistered> event) {
    BagRegistered bag = event.payload();
    EventReader.required(bag.bagTag(), "bagTag");
    if (bag.legs() == null || bag.legs().isEmpty()) {
      throw new PoisonEventException("legs are missing");
    }
    if (bag.legs().size() > MAX_LEGS) {
      throw new PoisonEventException("too many legs");
    }
    for (LegSnapshot leg : bag.legs()) {
      validLeg(leg);
    }
    return event;
  }

  /**
   * A leg as the journey-service validates it (its own formats, {@link SearchRules}): the id is
   * required, the other fields may be missing but must have their format when present. Checked up
   * front, so a leg the strict mapping or the keyword limits would refuse never reaches the bulk.
   */
  public static void validLeg(LegSnapshot leg) {
    if (leg == null) {
      throw new PoisonEventException("a leg is missing");
    }
    EventReader.required(leg.legId(), "legId");
    format(leg.flightNumber(), SearchRules.FLIGHT_PATTERN, "flightNumber");
    format(leg.airline(), SearchRules.AIRLINE_PATTERN, "airline");
    format(leg.origin(), SearchRules.AIRPORT_PATTERN, "origin");
    format(leg.destination(), SearchRules.AIRPORT_PATTERN, "destination");
  }

  private static void format(String value, String pattern, String field) {
    if (value != null && (value.length() > 16 || !value.matches(pattern))) {
      throw new PoisonEventException(field + " has an invalid format");
    }
  }
}
