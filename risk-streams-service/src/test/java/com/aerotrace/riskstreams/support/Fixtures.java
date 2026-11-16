package com.aerotrace.riskstreams.support;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed instants and a scan factory. All times are on the same UTC day, on 5-minute marks. */
public final class Fixtures {

  /** 2026-01-01T10:00:00Z: a multiple of 5 minutes, so it is a window start. */
  public static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

  private Fixtures() {}

  /**
   * An instant on the fixture day, for example {@code at("10:04:30")} or {@code
   * at("10:09:59.999")}.
   */
  public static Instant at(String time) {
    return Instant.parse("2026-01-01T" + time + "Z");
  }

  /** Creates scans with unique, deterministic scanEventIds (scan-1, scan-2, ...). */
  public static final class Scans {

    private final AtomicInteger sequence = new AtomicInteger();

    public ScanBuilder scan(com.aerotrace.common.model.Checkpoint checkpoint, Instant at) {
      return new ScanBuilder("scan-" + sequence.incrementAndGet()).checkpoint(checkpoint).at(at);
    }
  }
}
