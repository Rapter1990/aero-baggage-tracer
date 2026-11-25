package com.aerotrace.journey.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aerotrace.common.event.EventEnvelope;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.common.event.ScanReceivedPayload;
import com.aerotrace.common.event.Topics;
import com.aerotrace.common.security.Role;
import com.aerotrace.journey.base.AbstractApiTest;
import com.aerotrace.journey.model.entity.OutboxEventEntity;
import com.aerotrace.journey.repository.OutboxEventRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scans: accepted with 202 QUEUED, published through the outbox as baggage.scan.received.v1 and
 * idempotent on the Idempotency-Key; times are normalized to UTC with the source timezone kept,
 * scans arriving beyond the grace are flagged LATE_EVENT, and an older scan never replaces a newer
 * state while staying in the immutable history.
 */
class ScanAcceptanceTest extends AbstractApiTest {

  @Autowired private OutboxEventRepository outbox;

  private static final Role SCANNER = Role.AGENT;

  @Test
  void aScanIsQueuedAuditedAndStoredAsAnEventForPublishing() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    OffsetDateTime at = minutesAgo(1);

    scan(barcodeScan(bagTag, "IST", "CHECK_IN", 1, at), newKey(), as(SCANNER))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.bagTag").value(bagTag))
        .andExpect(jsonPath("$.data.lateEvent").value(false))
        .andExpect(jsonPath("$.data.duplicate").value(false));

    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagTag))
        .isEqualTo(1);
    // The entity writes the mandatory scan_source column (migration V2/V3): the HTTP API is 'API'.
    assertThat(
            count(
                "SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ? AND scan_source = 'API'",
                bagTag))
        .isEqualTo(1);
    String payload = scanEvents(bagTag).getFirst().getPayload();
    EventEnvelope<ScanReceivedPayload> event = EventJson.read(payload, ScanReceivedPayload.class);
    assertThat(event.eventType()).isEqualTo("baggage.scan.received");
    assertThat(event.schemaVersion()).isEqualTo(2);
    assertThat(event.aggregateId()).isEqualTo(bagTag);
    assertThat(event.payload().bagTag()).isEqualTo(bagTag);
    assertThat(event.payload().checkpoint()).isEqualTo("CHECK_IN");
    assertThat(event.payload().occurredAt())
        .isCloseTo(
            at.toInstant(),
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ? AND topic = ?",
                bagTag,
                Topics.SCAN_RECEIVED))
        .isEqualTo(1);
  }

  @Test
  void theScanEventCarriesTheLegTheScanBelongsTo() throws Exception {
    String bagTag = registeredBag(newPassengerRef());

    scan(barcodeScan(bagTag, ISTANBUL, "CHECK_IN", 1, minutesAgo(5)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());
    scan(barcodeScan(bagTag, FRANKFURT, "ARRIVED", 2, minutesAgo(4)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());
    scan(barcodeScan(bagTag, FRANKFURT, "TRANSFER", 3, minutesAgo(3)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());
    scan(barcodeScan(bagTag, LONDON, "CLAIM", 4, minutesAgo(2)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());

    List<ScanReceivedPayload> events =
        scanEvents(bagTag).stream()
            .map(e -> EventJson.read(e.getPayload(), ScanReceivedPayload.class).payload())
            .toList();
    assertThat(events)
        .extracting(
            ScanReceivedPayload::checkpoint,
            ScanReceivedPayload::legId,
            ScanReceivedPayload::flightNumber,
            ScanReceivedPayload::departureAt)
        .containsExactly(
            tuple("CHECK_IN", legId(bagTag, 1), "TK1234", departure(bagTag, 1)),
            tuple("ARRIVED", legId(bagTag, 1), "TK1234", departure(bagTag, 1)),
            tuple("TRANSFER", legId(bagTag, 2), "LH902", departure(bagTag, 2)),
            tuple("CLAIM", legId(bagTag, 2), "LH902", departure(bagTag, 2)));
  }

  @Test
  void aScanThatFitsNoLegIsStillAcceptedWithoutLegContext() throws Exception {
    String bagTag = registeredBag(newPassengerRef());

    // AMS is not on the itinerary; IST is an origin only, so nothing arrives there.
    scan(barcodeScan(bagTag, "AMS", "SORTING", 1, minutesAgo(3)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());
    scan(barcodeScan(bagTag, ISTANBUL, "ARRIVED", 2, minutesAgo(2)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());

    assertThat(scanEvents(bagTag))
        .hasSize(2)
        .allSatisfy(
            e -> {
              ScanReceivedPayload p =
                  EventJson.read(e.getPayload(), ScanReceivedPayload.class).payload();
              assertThat(p.legId()).isNull();
              assertThat(p.flightNumber()).isNull();
              assertThat(p.departureAt()).isNull();
            });
    journey(bagTag, as(Role.AGENT))
        .andExpect(jsonPath("$.data.history.length()").value(2))
        .andExpect(jsonPath("$.data.latestState.airport").value(ISTANBUL));
  }

  /** The scan events of a bag in the outbox (its registration event is there too, #28). */
  private List<OutboxEventEntity> scanEvents(String bagTag) {
    return outbox.findByMessageKeyOrderByCreatedAt(bagTag).stream()
        .filter(e -> Topics.SCAN_RECEIVED.equals(e.getTopic()))
        .toList();
  }

  private String legId(String bagTag, int legNo) {
    return legColumn(bagTag, legNo, "l.id").toString();
  }

  private Instant departure(String bagTag, int legNo) {
    Object value = legColumn(bagTag, legNo, "l.departure_at");
    return value instanceof Instant instant
        ? instant
        : value instanceof java.time.OffsetDateTime odt
            ? odt.toInstant()
            : ((java.sql.Timestamp) value).toInstant();
  }

  private Object legColumn(String bagTag, int legNo, String column) {
    return new TransactionTemplate(transactionManager)
        .execute(
            status ->
                entityManager
                    .createNativeQuery(
                        "SELECT "
                            + column
                            + " FROM journey.itinerary_leg l"
                            + " JOIN journey.itinerary i ON i.id = l.itinerary_id"
                            + " JOIN journey.baggage b ON b.id = i.baggage_id"
                            + " WHERE b.bag_tag = ?1 AND l.leg_no = ?2")
                    .setParameter(1, bagTag)
                    .setParameter(2, legNo)
                    .getSingleResult());
  }

  @Test
  void repeatingTheIdempotencyKeyReturnsTheOriginalAndCreatesNothingNew() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    String key = newKey();
    Map<String, Object> body = barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(1));

    String first = body(scan(body, key, as(SCANNER))).path("data").path("scanId").asString();
    scan(body, key, as(SCANNER))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.scanId").value(first))
        .andExpect(jsonPath("$.data.duplicate").value(true));

    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagTag))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ? AND topic = ?",
                bagTag,
                Topics.SCAN_RECEIVED))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM journey.processed_event WHERE event_id = ?", key))
        .isEqualTo(1);
  }

  @Test
  void parallelRetriesOfOneKeyProduceOneScanAndOneEvent() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    String key = newKey();
    Map<String, Object> body = barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(1));
    int callers = 8;
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      calls.add(() -> scan(body, key, as(SCANNER)).andReturn().getResponse().getStatus());
    }

    List<Integer> statuses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
      for (Future<Integer> result : pool.invokeAll(calls)) {
        statuses.add(result.get());
      }
    }

    assertThat(statuses).containsOnly(202);
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagTag))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM journey.outbox_event WHERE message_key = ? AND topic = ?",
                bagTag,
                Topics.SCAN_RECEIVED))
        .isEqualTo(1);
  }

  @Test
  void theIdempotencyKeyIsRequiredAndMustBeShortVisibleText() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    Map<String, Object> body = barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(1));

    for (String key : new String[] {null, "", "has space", "x".repeat(129)}) {
      scan(body, key, as(SCANNER))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagTag)).isZero();
  }

  @Test
  void aScanOfAnUnknownBagOrAnUnsupportedVersionIsRefused() throws Exception {
    scan(barcodeScan(newBagTag(), "IST", "CHECK_IN", 1, minutesAgo(1)), newKey(), as(SCANNER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    String bagTag = registeredBag(newPassengerRef());
    Map<String, Object> body = barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(1));
    body.put("version", 9);
    scan(body, newKey(), as(SCANNER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("That scan format version is not supported"));
  }

  @Test
  void rfidAndConveyorScansAreAcceptedThroughTheirOwnAdapters() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    Instant at = Instant.now().minusSeconds(60);

    Map<String, Object> rfid = new LinkedHashMap<>();
    rfid.put("protocol", "RFID");
    rfid.put(
        "payload",
        Map.of(
            "tagId",
            bagTag,
            "airport",
            "IST",
            "checkpoint",
            "SORTING",
            "readCount",
            2,
            "readAt",
            at.toEpochMilli(),
            "timezone",
            "Europe/Istanbul"));
    scan(rfid, newKey(), as(SCANNER)).andExpect(status().isAccepted());

    Map<String, Object> conveyor = new LinkedHashMap<>();
    conveyor.put("protocol", "CONVEYOR");
    conveyor.put(
        "payload",
        Map.of(
            "bagTag",
            bagTag,
            "airport",
            "IST",
            "checkpoint",
            "LOADED",
            "seq",
            3,
            "timestamp",
            at.plusSeconds(30)
                .atZone(java.time.ZoneId.of("Europe/Istanbul"))
                .toLocalDateTime()
                .toString(),
            "timezone",
            "Europe/Istanbul"));
    scan(conveyor, newKey(), as(SCANNER)).andExpect(status().isAccepted());

    journey(bagTag, as(Role.AGENT))
        .andExpect(jsonPath("$.data.history.length()").value(2))
        .andExpect(jsonPath("$.data.history[0].protocol").value("RFID"))
        .andExpect(jsonPath("$.data.history[0].sourceTimezone").value("Europe/Istanbul"))
        .andExpect(jsonPath("$.data.history[1].protocol").value("CONVEYOR"))
        .andExpect(jsonPath("$.data.latestState.checkpoint").value("LOADED"));
  }

  @Test
  void theTimeIsStoredInUtcAndTheSourceZoneAndDeviceTimeAreKept() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    OffsetDateTime local = OffsetDateTime.now(ZoneOffset.ofHours(3)).minusMinutes(2);

    scan(barcodeScan(bagTag, "IST", "CHECK_IN", 1, local), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());

    var entry = body(journey(bagTag, as(Role.AGENT))).path("data").path("history").get(0);
    assertThat(entry.path("sourceTimezone").asString()).isEqualTo("+03:00");
    assertThat(Instant.parse(entry.path("occurredAt").asString()))
        .isCloseTo(
            local.toInstant(),
            org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    assertThat(entry.path("deviceTimestamp").asString())
        .isEqualTo(entry.path("occurredAt").asString());
    assertThat(entry.path("lateEvent").asBoolean()).isFalse();
  }

  @Test
  void aScanOlderThanTheGraceOnArrivalIsFlaggedLateButStillAccepted() throws Exception {
    String bagTag = registeredBag(newPassengerRef());

    scan(barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(11)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.lateEvent").value(true));
    scan(barcodeScan(bagTag, "IST", "SORTING", 2, minutesAgo(9)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.lateEvent").value(false));

    assertThat(
            count(
                "SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ? AND late_event", bagTag))
        .isEqualTo(1);
    String payload = scanEvents(bagTag).getFirst().getPayload();
    assertThat(EventJson.read(payload, ScanReceivedPayload.class).payload().lateEvent()).isTrue();
  }

  @Test
  void anOlderScanNeverReplacesANewerStateButStaysInTheHistory() throws Exception {
    String bagTag = registeredBag(newPassengerRef());

    scan(barcodeScan(bagTag, "IST", "LOADED", 2, minutesAgo(1)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());
    // Arrives later, happened earlier.
    scan(barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(30)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.lateEvent").value(true));

    journey(bagTag, as(Role.AGENT))
        .andExpect(jsonPath("$.data.latestState.checkpoint").value("LOADED"))
        .andExpect(jsonPath("$.data.history.length()").value(2))
        .andExpect(jsonPath("$.data.history[0].checkpoint").value("CHECK_IN"))
        .andExpect(jsonPath("$.data.history[0].applied").value(false))
        .andExpect(jsonPath("$.data.history[1].checkpoint").value("LOADED"))
        .andExpect(jsonPath("$.data.history[1].applied").value(true));
  }

  @Test
  void aScanStampedFarInTheFutureIsRefused() throws Exception {
    String bagTag = registeredBag(newPassengerRef());

    scan(
            barcodeScan(
                bagTag, "IST", "CHECK_IN", 1, OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)),
            newKey(),
            as(SCANNER))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("The scan is stamped in the future"));
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagTag)).isZero();
  }

  @Test
  void theScanHistoryCanNotBeChangedOrDeleted() throws Exception {
    String bagTag = registeredBag(newPassengerRef());
    scan(barcodeScan(bagTag, "IST", "CHECK_IN", 1, minutesAgo(1)), newKey(), as(SCANNER))
        .andExpect(status().isAccepted());

    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            execute(
                "UPDATE journey.scan_audit SET checkpoint = 'CLAIM' WHERE bag_tag = :tag",
                Map.of("tag", bagTag)));
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            execute("DELETE FROM journey.scan_audit WHERE bag_tag = :tag", Map.of("tag", bagTag)));
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> execute("TRUNCATE journey.scan_audit", Map.of()));
    assertThat(count("SELECT count(*) FROM journey.scan_audit WHERE bag_tag = ?", bagTag))
        .isEqualTo(1);
  }
}
