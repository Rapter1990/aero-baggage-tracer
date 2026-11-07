package com.aerotrace.common.event;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/** JSON (de)serialization for event envelopes: ISO-8601 UTC timestamps, tolerant reader. */
public final class EventJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private EventJson() {}

  public static JsonMapper mapper() {
    return MAPPER;
  }

  public static String write(EventEnvelope<?> envelope) {
    return MAPPER.writeValueAsString(envelope);
  }

  public static <T> EventEnvelope<T> read(String json, Class<T> payloadType) {
    JavaType type =
        MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
    return MAPPER.readValue(json, type);
  }
}
