package com.aerotrace.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The replay dataset in {@code kafka/replay} (issue #15) stays valid: every line is a readable scan
 * event keyed by its bag tag, and the file still holds a duplicate, an out-of-order scan and a late
 * one, which is what the replay script is for.
 */
class ReplayDatasetTest {

  private static final Path DATASET =
      Path.of("..", "kafka", "replay", "scan-replay.ndjson").toAbsolutePath().normalize();
  private static final Duration GRACE = Duration.ofMinutes(10);

  private record Line(String key, EventEnvelope<ScanReceivedPayload> event) {}

  private static List<Line> lines() throws IOException {
    List<Line> lines = new ArrayList<>();
    for (String raw : Files.readAllLines(DATASET, StandardCharsets.UTF_8)) {
      if (raw.isBlank()) {
        continue;
      }
      int bar = raw.indexOf('|');
      lines.add(
          new Line(
              raw.substring(0, bar),
              EventJson.read(raw.substring(bar + 1), ScanReceivedPayload.class)));
    }
    return lines;
  }

  @Test
  @DisplayName("every line is a scan event whose key is its bag tag and whose version is readable")
  void everyLineIsAValidKeyedEvent() throws IOException {
    List<Line> lines = lines();

    assertThat(lines).isNotEmpty();
    assertThat(lines)
        .allSatisfy(
            line -> {
              assertThat(line.event().eventType()).isEqualTo("baggage.scan.received");
              assertThat(line.key()).isEqualTo(line.event().payload().bagTag());
              assertThat(line.event().aggregateId()).isEqualTo(line.key());
              line.event().requireSchemaVersionAtLeast(1);
            });
  }

  @Test
  @DisplayName("AT-03 the dataset holds a duplicate: one event id appears twice")
  void holdsADuplicate() throws IOException {
    Set<String> seen = new HashSet<>();
    long repeats = lines().stream().filter(l -> !seen.add(l.event().eventId())).count();

    assertThat(repeats).isEqualTo(1);
  }

  @Test
  @DisplayName("AT-04 the dataset holds a scan that arrives after a later one, inside the grace")
  void holdsAnOutOfOrderScanInsideTheGrace() throws IOException {
    List<Line> lines = lines();
    boolean found = false;
    for (int i = 1; i < lines.size(); i++) {
      var earlier = lines.get(i - 1).event().payload().occurredAt();
      var current = lines.get(i).event().payload().occurredAt();
      if (current.isBefore(earlier) && Duration.between(current, earlier).compareTo(GRACE) <= 0) {
        found = true;
      }
    }

    assertThat(found).isTrue();
  }

  @Test
  @DisplayName("AT-05 the dataset holds a scan older than the grace, flagged lateEvent")
  void holdsALateScanPastTheGrace() throws IOException {
    // Stream time is the latest event time seen so far, in arrival order.
    List<Line> late = new ArrayList<>();
    java.time.Instant streamTime = null;
    for (Line line : lines()) {
      var occurredAt = line.event().payload().occurredAt();
      if (streamTime != null && Duration.between(occurredAt, streamTime).compareTo(GRACE) > 0) {
        late.add(line);
      }
      if (streamTime == null || occurredAt.isAfter(streamTime)) {
        streamTime = occurredAt;
      }
    }

    assertThat(late).isNotEmpty();
    assertThat(late).allSatisfy(l -> assertThat(l.event().payload().lateEvent()).isTrue());
  }
}
