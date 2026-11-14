package com.aerotrace.journey.scan;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Conveyor controllers: {@code bagTag}, {@code airport}, {@code checkpoint}, {@code seq}, a local
 * {@code timestamp} without offset and the {@code timezone} (an IANA zone) it is local to.
 */
@Component
public class ConveyorScanHandler implements ScanHandler {

  @Override
  public ScanProtocol protocol() {
    return ScanProtocol.CONVEYOR;
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public NormalizedScan handle(Map<String, Object> payload) {
    LocalDateTime local = PayloadFields.localDateTime(payload, "timestamp");
    ZoneId zone = PayloadFields.zone(payload, "timezone");
    return new NormalizedScan(
        PayloadFields.text(payload, "bagTag"),
        PayloadFields.text(payload, "airport"),
        PayloadFields.checkpoint(payload, "checkpoint"),
        PayloadFields.nonNegativeLong(payload, "seq"),
        local.atZone(zone).toInstant(),
        zone.getId());
  }
}
