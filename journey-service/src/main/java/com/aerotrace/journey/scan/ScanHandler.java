package com.aerotrace.journey.scan;

import java.util.Map;

/**
 * Turns the payload of one device protocol, in one format version, into a {@link NormalizedScan}.
 * Each implementation is an Adapter between a device format and the domain.
 */
public interface ScanHandler {

  ScanProtocol protocol();

  /** The format version this handler reads. A new version is a new handler, not an if. */
  int version();

  /** Reads the payload; a missing or malformed field is a 400, never a guess. */
  NormalizedScan handle(Map<String, Object> payload);
}
