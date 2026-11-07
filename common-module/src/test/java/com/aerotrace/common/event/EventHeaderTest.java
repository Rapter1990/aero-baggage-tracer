package com.aerotrace.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventHeaderTest {

  @Test
  void readsVersionEvenWhenPayloadShapeIsUnknown() {
    String json =
        "{\"eventId\":\"e\",\"eventType\":\"baggage.risk.changed.v1\",\"schemaVersion\":9,"
            + "\"aggregateId\":\"a\",\"occurredAt\":\"2026-11-03T09:00:00Z\","
            + "\"publishedAt\":\"2026-11-03T09:00:01Z\",\"correlationId\":\"c\","
            + "\"payload\":{\"current\":\"BRAND_NEW_LEVEL\",\"previous\":42}}";

    var header = EventJson.readHeader(json);

    assertThat(header.schemaVersion()).isEqualTo(9);
    assertThat(header.eventType()).isEqualTo("baggage.risk.changed.v1");
  }
}
