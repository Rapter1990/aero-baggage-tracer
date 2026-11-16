package com.aerotrace.journey.scan;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.journey.constant.ApiMessages;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Reads typed fields from a device payload. Any problem is one fixed 400, echoing nothing. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PayloadFields {

  static String text(Map<String, Object> payload, String name) {
    if (payload.get(name) instanceof String value && !value.isBlank()) {
      return value.trim();
    }
    throw invalid();
  }

  static long nonNegativeLong(Map<String, Object> payload, String name) {
    if (payload.get(name) instanceof Number number
        && number.doubleValue() == number.longValue()
        && number.longValue() >= 0) {
      return number.longValue();
    }
    throw invalid();
  }

  static Checkpoint checkpoint(Map<String, Object> payload, String name) {
    return Checkpoint.parse(text(payload, name)).orElseThrow(PayloadFields::invalid);
  }

  /** An ISO-8601 time with an offset, for example 2026-11-14T08:15:00+02:00. */
  static OffsetDateTime offsetDateTime(Map<String, Object> payload, String name) {
    try {
      return OffsetDateTime.parse(text(payload, name));
    } catch (DateTimeException e) {
      throw invalid();
    }
  }

  /** An ISO-8601 time without an offset, for example 2026-11-14T08:15:00. */
  static LocalDateTime localDateTime(Map<String, Object> payload, String name) {
    try {
      return LocalDateTime.parse(text(payload, name));
    } catch (DateTimeException e) {
      throw invalid();
    }
  }

  /** An epoch time in milliseconds. */
  static Instant epochMillis(Map<String, Object> payload, String name) {
    try {
      return Instant.ofEpochMilli(nonNegativeLong(payload, name));
    } catch (DateTimeException e) {
      throw invalid();
    }
  }

  /** An IANA zone name such as Europe/Istanbul. */
  static ZoneId zone(Map<String, Object> payload, String name) {
    try {
      return ZoneId.of(text(payload, name));
    } catch (DateTimeException e) {
      throw invalid();
    }
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.VALIDATION_ERROR, ApiMessages.SCAN_PAYLOAD_INVALID);
  }
}
