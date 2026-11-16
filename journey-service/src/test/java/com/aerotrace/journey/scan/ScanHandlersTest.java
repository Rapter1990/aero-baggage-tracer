package com.aerotrace.journey.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanHandlersTest {

  private static final String TAG = "0123456789";
  private static final Instant NOON_UTC = Instant.parse("2026-11-14T12:00:00Z");

  private final BarcodeScanHandler barcode = new BarcodeScanHandler();
  private final RfidScanHandler rfid = new RfidScanHandler();
  private final ConveyorScanHandler conveyor = new ConveyorScanHandler();

  @Test
  void barcodeKeepsTheOffsetAndNormalizesTheTimeToUtc() {
    NormalizedScan scan =
        barcode.handle(
            Map.of(
                "bagTag", TAG,
                "airport", "IST",
                "checkpoint", "loaded",
                "sequence", 7,
                "scannedAt", "2026-11-14T15:00:00+03:00"));

    assertThat(scan.bagTag()).isEqualTo(TAG);
    assertThat(scan.checkpoint()).isEqualTo(Checkpoint.LOADED);
    assertThat(scan.deviceSequence()).isEqualTo(7);
    assertThat(scan.occurredAt()).isEqualTo(NOON_UTC);
    assertThat(scan.sourceTimezone()).isEqualTo("+03:00");
  }

  @Test
  void rfidReadsEpochMillisAndTheNamedZone() {
    NormalizedScan scan =
        rfid.handle(
            Map.of(
                "tagId",
                TAG,
                "airport",
                "FRA",
                "checkpoint",
                "SORTING",
                "readCount",
                3,
                "readAt",
                NOON_UTC.toEpochMilli(),
                "timezone",
                "Europe/Berlin"));

    assertThat(scan.occurredAt()).isEqualTo(NOON_UTC);
    assertThat(scan.sourceTimezone()).isEqualTo("Europe/Berlin");
    assertThat(scan.checkpoint()).isEqualTo(Checkpoint.SORTING);
  }

  @Test
  void conveyorTurnsALocalTimeIntoUtcUsingItsZone() {
    NormalizedScan scan =
        conveyor.handle(
            Map.of(
                "bagTag", TAG,
                "airport", "IST",
                "checkpoint", "TRANSFER",
                "seq", 1,
                "timestamp", "2026-11-14T15:00:00",
                "timezone", "Europe/Istanbul"));

    assertThat(scan.occurredAt()).isEqualTo(NOON_UTC);
    assertThat(scan.sourceTimezone()).isEqualTo("Europe/Istanbul");
  }

  @Test
  void everyProtocolRefusesAMissingOrMalformedField() {
    Map<String, Object> valid = new HashMap<>();
    valid.put("bagTag", TAG);
    valid.put("airport", "IST");
    valid.put("checkpoint", "LOADED");
    valid.put("sequence", 1);
    valid.put("scannedAt", "2026-11-14T15:00:00+03:00");

    for (String field : List.of("bagTag", "airport", "checkpoint", "sequence", "scannedAt")) {
      Map<String, Object> missing = new HashMap<>(valid);
      missing.remove(field);
      assertInvalid(() -> barcode.handle(missing));
    }
    assertInvalid(() -> barcode.handle(with(valid, "bagTag", "12345")));
    assertInvalid(() -> barcode.handle(with(valid, "airport", "ist")));
    assertInvalid(() -> barcode.handle(with(valid, "checkpoint", "LUNCH")));
    assertInvalid(() -> barcode.handle(with(valid, "sequence", -1)));
    assertInvalid(() -> barcode.handle(with(valid, "sequence", 1.5)));
    assertInvalid(() -> barcode.handle(with(valid, "scannedAt", "yesterday")));
    assertInvalid(() -> barcode.handle(with(valid, "scannedAt", "2026-11-14T15:00:00")));

    assertInvalid(() -> rfid.handle(Map.of("tagId", TAG)));
    assertInvalid(
        () ->
            rfid.handle(
                Map.of(
                    "tagId",
                    TAG,
                    "airport",
                    "FRA",
                    "checkpoint",
                    "SORTING",
                    "readCount",
                    1,
                    "readAt",
                    1000L,
                    "timezone",
                    "Mars/Olympus")));
    assertInvalid(() -> conveyor.handle(Map.of("bagTag", TAG)));
  }

  @Test
  void factoryFindsTheHandlerOfAProtocolAndVersionOrRefuses() {
    ScanHandlerFactory factory = new ScanHandlerFactory(List.of(barcode, rfid, conveyor));

    assertThat(factory.create(ScanProtocol.BARCODE, 1)).isSameAs(barcode);
    assertThat(factory.create(ScanProtocol.RFID, 1)).isSameAs(rfid);
    assertThat(factory.create(ScanProtocol.CONVEYOR, 1)).isSameAs(conveyor);
    assertInvalid(() -> factory.create(ScanProtocol.BARCODE, 2));
  }

  @Test
  void checkpointParsingIsCaseInsensitiveAndRefusesUnknownNames() {
    assertThat(Checkpoint.parse(" claim ")).contains(Checkpoint.CLAIM);
    assertThat(Checkpoint.parse("nowhere")).isEmpty();
    assertThat(Checkpoint.parse(null)).isEmpty();
  }

  private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
    Map<String, Object> copy = new HashMap<>(base);
    copy.put(key, value);
    return copy;
  }

  private static void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
  }
}
