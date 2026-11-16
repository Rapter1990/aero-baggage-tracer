package com.aerotrace.journey.scan;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.JourneyRules;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * A scan in the one shape the rest of the service understands, whatever device it came from. The
 * time is a UTC instant; {@code sourceTimezone} keeps the zone or offset the device reported.
 */
public record NormalizedScan(
    String bagTag,
    String airport,
    Checkpoint checkpoint,
    long deviceSequence,
    Instant deviceTimestamp,
    String sourceTimezone) {

  private static final Pattern BAG_TAG = Pattern.compile(JourneyRules.BAG_TAG_PATTERN);
  private static final Pattern AIRPORT = Pattern.compile(JourneyRules.AIRPORT_PATTERN);

  public NormalizedScan {
    if (bagTag == null
        || !BAG_TAG.matcher(bagTag).matches()
        || airport == null
        || !AIRPORT.matcher(airport).matches()
        || checkpoint == null
        || deviceSequence < 0
        || deviceTimestamp == null
        || sourceTimezone == null
        || sourceTimezone.isBlank()
        || sourceTimezone.length() > 40) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.SCAN_PAYLOAD_INVALID);
    }
    // The precision the database keeps, so comparing with a stored time never depends on rounding.
    deviceTimestamp = deviceTimestamp.truncatedTo(ChronoUnit.MICROS);
  }

  /** The event time in UTC. */
  public Instant occurredAt() {
    return deviceTimestamp;
  }
}
