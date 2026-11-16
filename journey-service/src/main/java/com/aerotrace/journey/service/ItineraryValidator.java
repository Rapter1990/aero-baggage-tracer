package com.aerotrace.journey.service;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.journey.config.JourneyProperties;
import com.aerotrace.journey.constant.ApiMessages;
import com.aerotrace.journey.model.dto.request.LegRequest;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The business rules of an itinerary that format validation cannot see: every leg flies somewhere
 * and takes time, the legs are chained (each starts where the last ended), they are in
 * chronological order and every connection is at least the minimum connection time.
 */
@Component
public class ItineraryValidator {

  private final JourneyProperties properties;

  public ItineraryValidator(JourneyProperties properties) {
    this.properties = properties;
  }

  public void validate(List<LegRequest> legs) {
    LegRequest previous = null;
    for (LegRequest leg : legs) {
      if (leg.origin().equals(leg.destination())) {
        throw reject(ApiMessages.LEG_AIRPORTS);
      }
      if (!leg.arrivalAt().isAfter(leg.departureAt())) {
        throw reject(ApiMessages.LEG_TIMES);
      }
      if (previous != null) {
        if (!leg.origin().equals(previous.destination())) {
          throw reject(ApiMessages.LEG_CONTINUITY);
        }
        if (leg.departureAt().isBefore(previous.arrivalAt())) {
          throw reject(ApiMessages.LEG_ORDER);
        }
        if (leg.departureAt()
            .isBefore(previous.arrivalAt().plus(properties.minimumConnectionTime()))) {
          throw reject(ApiMessages.MINIMUM_CONNECTION);
        }
      }
      previous = leg;
    }
  }

  private static ApiException reject(String message) {
    return new ApiException(ErrorCode.VALIDATION_ERROR, message);
  }
}
