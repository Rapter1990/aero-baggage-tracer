package com.aerotrace.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.common.export.BagExport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The contract of {@code baggage.bag.registered.v1} and of the bag export's items. */
class BagRegisteredContractTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);
  private static final LegSnapshot FIRST =
      new LegSnapshot(
          "6f1c2a7e-0d4b-4a39-9b8e-3c2f1e0a9d11",
          "TK1981",
          "TK",
          "IST",
          "FRA",
          Instant.parse("2026-11-04T08:00:00Z"));
  private static final LegSnapshot SECOND =
      new LegSnapshot(
          "0a2b3c4d-0d4b-4a39-9b8e-3c2f1e0a9d12",
          "LH902",
          "LH",
          "FRA",
          "LHR",
          Instant.parse("2026-11-04T12:00:00Z"));

  @Test
  @DisplayName("the topic is baggage.bag.registered.v1 and is one of Topics.ALL")
  void topicIsShared() {
    assertThat(Topics.BAG_REGISTERED).isEqualTo("baggage.bag.registered.v1");
    assertThat(Topics.ALL).contains(Topics.BAG_REGISTERED);
  }

  @Test
  @DisplayName("a registration event round-trips with every leg snapshot, airline included")
  void registrationRoundTrip() {
    var payload = new BagRegisteredPayload("0123456789", List.of(FIRST, SECOND));
    var envelope =
        EventEnvelope.create(
            "baggage.bag.registered",
            1,
            "0123456789",
            "corr-1",
            Instant.parse("2026-11-03T09:00:00Z"),
            payload,
            CLOCK);

    String json = EventJson.write(envelope);

    assertThat(json)
        .contains("\"airline\":\"TK\"", "\"origin\":\"IST\"", "\"destination\":\"FRA\"")
        .contains("\"departureAt\":\"2026-11-04T08:00:00Z\"")
        .doesNotContain("passenger");
    assertThat(EventJson.read(json, BagRegisteredPayload.class)).isEqualTo(envelope);
  }

  @Test
  @DisplayName("an exported bag reads its legs and latest scan; a bag never scanned has none")
  void exportItemRoundTrip() {
    var scanned =
        new BagExport(
            "0123456789",
            List.of(FIRST, SECOND),
            new BagExport.LatestScan(
                "FRA", "TRANSFER", SECOND.legId(), Instant.parse("2026-11-04T11:00:00Z"), 7));
    var fresh = new BagExport("0123456780", List.of(FIRST), null);
    var mapper = EventJson.mapper();

    assertThat(mapper.readValue(mapper.writeValueAsString(scanned), BagExport.class))
        .isEqualTo(scanned);
    String json = mapper.writeValueAsString(fresh);
    assertThat(json).contains("\"latestScan\":null");
    assertThat(mapper.readValue(json, BagExport.class)).isEqualTo(fresh);
  }
}
