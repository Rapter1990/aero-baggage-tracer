package com.aerotrace.journey.model.dto.request;

import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.scan.ScanProtocol;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * A scan as a device reports it. {@code payload} has the format of the protocol and version (see
 * the handlers); {@code version} defaults to 1.
 */
public record ScanRequest(
    @NotNull ScanProtocol protocol,
    @Min(1) @Max(1000) Integer version,
    @NotNull @Size(max = JourneyRules.MAX_PAYLOAD_FIELDS) Map<String, Object> payload) {

  public int versionOrDefault() {
    return version == null ? 1 : version;
  }
}
