package com.aerotrace.common.event;

/** Thrown when an event's schemaVersion is older than the minimum the consumer requires. */
public class UnsupportedSchemaVersionException extends RuntimeException {

  public UnsupportedSchemaVersionException(String eventType, int actual, int minRequired) {
    super(
        "Event type %s has schemaVersion %d, consumer requires at least %d"
            .formatted(eventType, actual, minRequired));
  }
}
