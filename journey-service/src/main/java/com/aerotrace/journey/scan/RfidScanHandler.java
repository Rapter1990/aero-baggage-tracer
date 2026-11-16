package com.aerotrace.journey.scan;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * RFID gates: {@code tagId} (the bag tag), {@code airport}, {@code checkpoint}, {@code readCount}
 * (the reader sequence), {@code readAt} (epoch milliseconds) and {@code timezone} (an IANA zone).
 */
@Component
public class RfidScanHandler implements ScanHandler {

  @Override
  public ScanProtocol protocol() {
    return ScanProtocol.RFID;
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public NormalizedScan handle(Map<String, Object> payload) {
    Instant readAt = PayloadFields.epochMillis(payload, "readAt");
    ZoneId zone = PayloadFields.zone(payload, "timezone");
    return new NormalizedScan(
        PayloadFields.text(payload, "tagId"),
        PayloadFields.text(payload, "airport"),
        PayloadFields.checkpoint(payload, "checkpoint"),
        PayloadFields.nonNegativeLong(payload, "readCount"),
        readAt,
        zone.getId());
  }
}
