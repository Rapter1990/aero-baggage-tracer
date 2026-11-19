package com.aerotrace.recovery.capacity;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.event.EventJson;
import com.aerotrace.recovery.constant.RecoveryRules;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * The recovery-service's view of the capacity-service, with an explicit fallback for every failure.
 * Answers are told apart by the {@code code} of the error body, not by the HTTP status alone:
 *
 * <ul>
 *   <li>{@code CAPACITY_EXCEEDED} (409): the leg is full, nothing was held;
 *   <li>{@code CAPACITY_NOT_PROVISIONED} (404): the leg has no resource. The caller still hears "no
 *       capacity", but it is counted and logged apart, because it is a set-up problem and not a
 *       full leg;
 *   <li>any other failure on reserve (no answer, a timeout, an unexpected answer): the hold may
 *       exist without us knowing, so it is released by its id (compensation) and the caller gets
 *       {@code CAPACITY_UNAVAILABLE} (503), a clear "try again" instead of a half-done move;
 *   <li>a release that fails is reported as {@code false}, and the hold stays recorded on the case
 *       so the sweeper can release it later;
 *   <li>a confirmation that fails is reported as {@code false} and repeated by the sweeper.
 * </ul>
 *
 * <p>A release of a hold the capacity-service never saw is safe: it records the id as released, so
 * a hold that was only slow can no longer arrive and stay forever.
 *
 * <p>The sweeper has no user token. Its calls carry the shared service token ({@code
 * X-Service-Token}, see {@code CapacityClientConfiguration}), which the capacity-service accepts
 * for release and confirmation; without it the hold of a timed-out case stays until it is released
 * by hand.
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
    } catch (HttpClientErrorException rejected) {
      String code = errorCode(rejected);
      if (rejected.getStatusCode().value() == 409 && "CAPACITY_EXCEEDED".equals(code)) {
        meters.counter("recovery.capacity.reserve", "outcome", "exceeded").increment();
        throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, RecoveryRules.CAPACITY_EXCEEDED);
      }
      if (rejected.getStatusCode().value() == 404 && "CAPACITY_NOT_PROVISIONED".equals(code)) {
        meters.counter("recovery.capacity.reserve", "outcome", "not_provisioned").increment();
        LOG.error("The capacity-service has no capacity resource for leg {}", legId);
        throw new ApiException(ErrorCode.CAPACITY_EXCEEDED, RecoveryRules.CAPACITY_EXCEEDED);
      }
      throw unavailable(reservationId, rejected);
    } catch (RestClientException failure) {
      throw unavailable(reservationId, failure);
    }
  }

  private ApiException unavailable(UUID reservationId, RestClientException failure) {
    meters.counter("recovery.capacity.reserve", "outcome", "unavailable").increment();
    LOG.warn(
        "The capacity-service could not reserve a slot: {}", failure.getClass().getSimpleName());
    // The answer may have been lost after the hold was made: release by id.
    release(reservationId);
    return new ApiException(ErrorCode.CAPACITY_UNAVAILABLE, RecoveryRules.CAPACITY_UNAVAILABLE);
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

  /**
   * Tells the capacity-service that the case kept its hold, so the capacity-service's expiry never
   * takes it. True when it is done, or when there is nothing left to confirm (the hold is already
   * released or expired: repeating would never help, so it is counted and logged once); false to
   * try again.
   */
  public boolean confirm(UUID reservationId) {
    try {
      client.confirm(reservationId);
      meters.counter("recovery.capacity.confirm", "outcome", "confirmed").increment();
      return true;
    } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Conflict lost) {
      meters.counter("recovery.capacity.confirm", "outcome", "lost").increment();
      LOG.error("A capacity hold of an active case is gone and cannot be confirmed");
      return true;
    } catch (RestClientException failure) {
      meters.counter("recovery.capacity.confirm", "outcome", "failed").increment();
      LOG.warn("A capacity hold could not be confirmed: {}", failure.getClass().getSimpleName());
      return false;
    }
  }

  /** The {@code code} of an error body, or an empty string when there is none. */
  private static String errorCode(HttpClientErrorException failure) {
    try {
      JsonNode code = EventJson.mapper().readTree(failure.getResponseBodyAsString()).get("code");
      return code == null ? "" : code.asString();
    } catch (RuntimeException unreadable) {
      return "";
    }
  }
}
