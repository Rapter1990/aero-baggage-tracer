package com.aerotrace.common.model;

import java.util.Locale;
import java.util.Optional;

/** The places a bag is scanned along its journey, in the order of a normal trip. */
public enum Checkpoint {
  CHECK_IN,
  SORTING,
  TRANSFER,
  LOADED,
  ARRIVED,
  CLAIM;

  /** Parses a device value ("loaded", "LOADED"); unknown values yield empty. */
  public static Optional<Checkpoint> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (Checkpoint checkpoint : values()) {
      if (checkpoint.name().equals(normalized)) {
        return Optional.of(checkpoint);
      }
    }
    return Optional.empty();
  }
}
