package com.aerotrace.journey.scan;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Barcode readers (check-in desks, sorting lines): {@code bagTag}, {@code airport}, {@code
 * checkpoint}, {@code sequence} and {@code scannedAt}, an ISO-8601 time with its offset.
 */
@Component
public class BarcodeScanHandler implements ScanHandler {

  @Override
  public ScanProtocol protocol() {
    return ScanProtocol.BARCODE;
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public NormalizedScan handle(Map<String, Object> payload) {
    OffsetDateTime scannedAt = PayloadFields.offsetDateTime(payload, "scannedAt");
    return new NormalizedScan(
        PayloadFields.text(payload, "bagTag"),
        PayloadFields.text(payload, "airport"),
        PayloadFields.checkpoint(payload, "checkpoint"),
        PayloadFields.nonNegativeLong(payload, "sequence"),
        scannedAt.toInstant(),
        scannedAt.getOffset().getId());
  }
}
