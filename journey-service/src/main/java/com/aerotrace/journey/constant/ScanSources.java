package com.aerotrace.journey.constant;

import lombok.experimental.UtilityClass;

/**
 * The values of {@code scan_audit.scan_source}: how a scan reached the service (ADR 0007). The
 * column is mandatory; the HTTP API is the only way in today.
 */
@UtilityClass
public class ScanSources {

  /** A scan received through the HTTP API. */
  public static final String API = "API";
}
