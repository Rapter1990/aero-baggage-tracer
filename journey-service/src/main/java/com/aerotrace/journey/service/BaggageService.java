package com.aerotrace.journey.service;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.constant.JourneyRules;
import com.aerotrace.journey.model.dto.request.LegRequest;
import com.aerotrace.journey.model.dto.request.RegisterBagRequest;
import com.aerotrace.journey.model.dto.response.JourneyResponse;
import com.aerotrace.journey.model.dto.response.LatestStateResponse;
import com.aerotrace.journey.model.dto.response.LegResponse;
import com.aerotrace.journey.model.dto.response.RegisteredBagResponse;
import com.aerotrace.journey.model.dto.response.ScanHistoryEntry;
import com.aerotrace.journey.model.entity.BaggageEntity;
import com.aerotrace.journey.model.entity.ItineraryEntity;
import com.aerotrace.journey.model.entity.ItineraryLegEntity;
import com.aerotrace.journey.model.entity.ScanAuditEntity;
import com.aerotrace.journey.repository.BaggageRepository;
import com.aerotrace.journey.repository.ItineraryRepository;
import com.aerotrace.journey.repository.ScanAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers bags and answers the journey query, with row-level ownership for passengers. */
@Service
public class BaggageService {

  private static final Set<Role> STAFF = EnumSet.of(Role.AGENT, Role.DISPATCHER, Role.ADMIN);

  private final BaggageRepository baggage;
  private final ItineraryRepository itineraries;
  private final ScanAuditRepository scans;
  private final ItineraryValidator validator;
  private final Clock clock;

  public BaggageService(
      BaggageRepository baggage,
      ItineraryRepository itineraries,
      ScanAuditRepository scans,
      ItineraryValidator validator,
      Clock clock) {
    this.baggage = baggage;
    this.itineraries = itineraries;
    this.scans = scans;
    this.validator = validator;
    this.clock = clock;
  }

  /** Registers a bag with its itinerary snapshot; a bag tag can be registered only once. */
  @Transactional
  public RegisteredBagResponse register(RegisterBagRequest request) {
    validator.validate(request.legs());
    if (baggage.existsByBagTag(request.bagTag())) {
      throw duplicate();
    }
    Instant now = Instant.now(clock);
    BaggageEntity bag;
    try {
      // Flushed here so a concurrent registration of the same tag is reported as a duplicate.
      bag = baggage.saveAndFlush(new BaggageEntity(request.bagTag(), request.passengerRef(), now));
    } catch (DataIntegrityViolationException e) {
      throw duplicate();
    }
    ItineraryEntity itinerary =
        itineraries.save(new ItineraryEntity(bag.getId(), now, toLegs(request.legs())));
    return new RegisteredBagResponse(
        bag.getBagTag(),
        bag.getRegisteredAt(),
        itinerary.getLegs().stream().map(LegResponse::from).toList());
  }

  /**
   * The latest accepted state and the immutable history. A passenger sees only their own bag; any
   * other bag answers exactly like one that does not exist, so tags cannot be probed.
   */
  @Transactional(readOnly = true)
  public JourneyResponse journey(String bagTag) {
    BaggageEntity bag = baggage.findByBagTag(bagTag).orElseThrow(BaggageService::notFound);
    if (!mayView(bag)) {
      throw notFound();
    }
    List<LegResponse> legs =
        itineraries
            .findByBaggageId(bag.getId())
            .map(i -> i.getLegs().stream().map(LegResponse::from).toList())
            .orElse(List.of());
    // One more than the cap, to know whether older scans were left out.
    List<ScanAuditEntity> newest =
        scans.findByBagTagOrderByOccurredAtDescIngestedAtDesc(
            bagTag, PageRequest.of(0, JourneyRules.MAX_HISTORY + 1));
    boolean truncated = newest.size() > JourneyRules.MAX_HISTORY;
    List<ScanHistoryEntry> history =
        new ArrayList<>(
            newest.subList(0, Math.min(newest.size(), JourneyRules.MAX_HISTORY)).stream()
                .map(ScanHistoryEntry::from)
                .toList());
    Collections.reverse(history);
    LatestStateResponse latest =
        bag.getLatestScanId() == null
            ? null
            : new LatestStateResponse(
                bag.getLatestScanId(),
                bag.getLatestAirport(),
                bag.getLatestCheckpoint(),
                bag.getLatestOccurredAt());
    return new JourneyResponse(bag.getBagTag(), latest, legs, history, truncated);
  }

  private static boolean mayView(BaggageEntity bag) {
    if (SecurityContextHelper.currentRoles().stream().anyMatch(STAFF::contains)) {
      return true;
    }
    return SecurityContextHelper.currentPassengerRef()
        .map(ref -> ref.equals(bag.getPassengerRef()))
        .orElse(false);
  }

  private static List<ItineraryLegEntity> toLegs(List<LegRequest> legs) {
    List<ItineraryLegEntity> result = new ArrayList<>();
    int number = 1;
    for (LegRequest leg : legs) {
      result.add(
          new ItineraryLegEntity(
              number++,
              leg.flightNumber(),
              leg.origin(),
              leg.destination(),
              leg.departureAt(),
              leg.arrivalAt()));
    }
    return result;
  }

  private static ApiException duplicate() {
    return new ApiException(ErrorCode.BAG_TAG_DUPLICATE, ApiMessages.BAG_TAG_DUPLICATE);
  }

  private static ApiException notFound() {
    return new ApiException(ErrorCode.NOT_FOUND, ApiMessages.BAG_NOT_FOUND);
  }
}
