package com.aerotrace.journey.service;

import com.aerotrace.common.export.BagExport;
import com.aerotrace.common.export.ExportPage;
import com.aerotrace.common.model.Checkpoint;
import com.aerotrace.journey.model.entity.BaggageEntity;
import com.aerotrace.journey.model.entity.ItineraryEntity;
import com.aerotrace.journey.model.entity.ItineraryLegEntity;
import com.aerotrace.journey.repository.BaggageRepository;
import com.aerotrace.journey.repository.ItineraryRepository;
import com.aerotrace.journey.scan.ScanLegResolver;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The internal bag export the search-service rebuilds from (ADR 0004, decision 1). Keyset paging by
 * the bag tag (unique and never changed): every bag that exists for the whole run is returned
 * exactly once, whatever is registered meanwhile, and a bag registered meanwhile at most once. Two
 * queries per page (the bags, then their itineraries with the legs). Read-only: a read-only
 * transaction and no path that writes. No passenger data leaves this way.
 */
@Service
public class BagExportService {

  private final BaggageRepository baggage;
  private final ItineraryRepository itineraries;

  public BagExportService(BaggageRepository baggage, ItineraryRepository itineraries) {
    this.baggage = baggage;
    this.itineraries = itineraries;
  }

  /**
   * The bags after {@code after} (null for the first page), at most {@code size}, in tag order.
   * {@code nextAfter} is the last tag when more bags follow, otherwise null.
   */
  @Transactional(readOnly = true)
  public ExportPage<BagExport> page(String after, int size) {
    // One more than asked, to know whether another page follows.
    Limit limit = Limit.of(size + 1);
    List<BaggageEntity> rows =
        after == null
            ? baggage.findAllByOrderByBagTagAsc(limit)
            : baggage.findByBagTagGreaterThanOrderByBagTagAsc(after, limit);
    boolean more = rows.size() > size;
    List<BaggageEntity> page = more ? rows.subList(0, size) : rows;
    Map<UUID, ItineraryEntity> byBag =
        itineraries
            .findWithLegsByBaggageIdIn(page.stream().map(BaggageEntity::getId).toList())
            .stream()
            .collect(Collectors.toMap(ItineraryEntity::getBaggageId, Function.identity()));
    List<BagExport> items =
        page.stream().map(bag -> export(bag, legsOf(byBag.get(bag.getId())))).toList();
    return new ExportPage<>(items, more ? page.getLast().getBagTag() : null);
  }

  private static List<ItineraryLegEntity> legsOf(ItineraryEntity itinerary) {
    if (itinerary == null) {
      return List.of();
    }
    return itinerary.getLegs().stream()
        .sorted(Comparator.comparingInt(ItineraryLegEntity::getLegNo))
        .toList();
  }

  private static BagExport export(BaggageEntity bag, List<ItineraryLegEntity> legs) {
    BagExport.LatestScan latest = null;
    if (bag.getLatestScanId() != null) {
      // The leg by the same rule as the scan event (ScanLegResolver).
      String legId =
          ScanLegResolver.resolve(
                  legs,
                  bag.getLatestAirport(),
                  Checkpoint.valueOf(bag.getLatestCheckpoint()),
                  bag.getLatestOccurredAt())
              .map(leg -> leg.getId().toString())
              .orElse(null);
      latest =
          new BagExport.LatestScan(
              bag.getLatestAirport(),
              bag.getLatestCheckpoint(),
              legId,
              bag.getLatestOccurredAt(),
              bag.getLatestDeviceSequence());
    }
    return new BagExport(bag.getBagTag(), legs.stream().map(LegSnapshots::of).toList(), latest);
  }
}
