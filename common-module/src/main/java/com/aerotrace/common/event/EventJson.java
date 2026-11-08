package com.aerotrace.common.event;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON (de)serialization for event envelopes: ISO-8601 UTC timestamps, tolerant reader. */
public final class EventJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          // Enums on the wire are open: a constant added by a newer producer reads as null here,
          // and consumers need a default branch that logs and skips it.
          .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
          .build();

  private EventJson() {}

  public static JsonMapper mapper() {
    return MAPPER;
  }

  public static String write(EventEnvelope<?> envelope) {
    return MAPPER.writeValueAsString(envelope);
  }

  /**
   * Reads only eventType and schemaVersion, without binding the payload. Consumers check the
   * version here first so a newer payload shape cannot fail typed binding before the check.
   */
  public static Header readHeader(String json) {
    return MAPPER.readValue(json, Header.class);
  }

  public record Header(String eventType, int schemaVersion) {}

  public static <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
    JavaType type =
        MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
    return MAPPER.readValue(json, type);
  }
}
