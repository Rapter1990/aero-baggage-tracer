package com.aerotrace.common.event;

/** Thrown when an event's schemaVersion is newer than the consumer understands. */
public class UnsupportedSchemaVersionException extends RuntimeException {

  public UnsupportedSchemaVersionException(String eventType, int actual, int maxSupported) {
    super(
        "Event type %s has schemaVersion %d, consumer supports up to %d"
            .formatted(eventType, actual, maxSupported));
  }
}
