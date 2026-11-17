package com.aerotrace.riskstreams;

import com.aerotrace.common.event.EventJson;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/** JSON serde for state-store values, on the same mapper as the events. No Java serialization. */
final class JsonSerde<T> implements Serde<T> {

  private final Class<T> type;

  JsonSerde(Class<T> type) {
    this.type = type;
  }

  @Override
  public Serializer<T> serializer() {
    return (topic, value) -> value == null ? null : EventJson.mapper().writeValueAsBytes(value);
  }

  @Override
  public Deserializer<T> deserializer() {
    return (topic, bytes) -> bytes == null ? null : EventJson.mapper().readValue(bytes, type);
  }
}
