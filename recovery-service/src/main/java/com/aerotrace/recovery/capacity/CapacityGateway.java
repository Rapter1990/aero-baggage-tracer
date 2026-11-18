package com.aerotrace.recovery.capacity;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.recovery.constant.RecoveryRules;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * The recovery-service's view of the capacity-service, with an explicit fallback for every failure:
 *
 * <ul>
 *   <li>capacity used up (409): {@code CAPACITY_EXCEEDED}, nothing was held;
 *   <li>no answer, a timeout or any other failure on reserve: the hold may exist without us
 *       knowing, so it is released by its id (compensation) and the caller gets {@code
 *       CAPACITY_UNAVAILABLE} (503), a clear "try again" instead of a half-done move;
 *   <li>a release that fails is reported as {@code false}, and the hold stays recorded on the case
 *       so the sweeper can release it later.
 * </ul>
 *
 * <p>The sweeper has no user token. Until the identity-service issues service tokens, the
 * capacity-service must accept its release calls without one, or the hold of a timed-out case stays
 * until it is released by hand.
 */
@Component
public class CapacityGateway {

  private static final Logger LOG = LoggerFactory.getLogger(CapacityGateway.class);

  private final CapacityClient client;
  private final MeterRegistry meters;

  public CapacityGateway(CapacityClient client, MeterRegistry meters) {
    this.client = client;
    this.meters = meters;
  }

  /** Holds one slot for the case and returns the reservation id. */
  public UUID reserve(UUID caseId, String bagTag, String legId) {
    UUID reservationId = UUID.randomUUID();
    try {
      client.reserve(
          new CapacityClient.ReservationRequest(reservationId, caseId, bagTag, legId, 1));
      return reservationId;
    } catch (HttpClientErrorException.Conflict full) {
      meters.counter("recovery.capacity.reserve", "outcome", "exceeded").increment();
      throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, RecoveryRules.CAPACITY_EXCEEDED);
    } catch (RestClientException failure) {
      meters.counter("recovery.capacity.reserve", "outcome", "unavailable").increment();
      LOG.warn(
          "The capacity-service could not reserve a slot: {}", failure.getClass().getSimpleName());
      // The answer may have been lost after the hold was made: release by id.
      release(reservationId);
      throw new ApiException(ErrorCode.CAPACITY_UNAVAILABLE, RecoveryRules.CAPACITY_UNAVAILABLE);
    }
  }

  /** Releases a hold. True when it is gone (released now or never there); false to try again. */
  public boolean release(UUID reservationId) {
    try {
      client.release(reservationId);
      meters.counter("recovery.capacity.release", "outcome", "released").increment();
      return true;
    } catch (HttpClientErrorException.NotFound gone) {
      return true;
    } catch (RestClientException failure) {
      meters.counter("recovery.capacity.release", "outcome", "failed").increment();
      LOG.warn("A capacity hold could not be released: {}", failure.getClass().getSimpleName());
      return false;
    }
  }
}
