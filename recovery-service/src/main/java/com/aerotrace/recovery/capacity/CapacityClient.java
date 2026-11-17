package com.aerotrace.recovery.capacity;

import com.aerotrace.common.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The typed HTTP client of the capacity-service (spec: POST /capacity/reservations and DELETE
 * /capacity/reservations/{id}). The reservation id is chosen by the caller, so a hold whose answer
 * was lost (a timeout) can still be released by id.
 */
@HttpExchange("/api/v1/capacity/reservations")
public interface CapacityClient {

  /** Holds slots; answers 409 when the capacity is used up. */
  @PostExchange
  ApiResponse<ReservationResponse> reserve(@RequestBody ReservationRequest request);

  /** Releases a hold; answers 404 when there is none (already released). */
  @DeleteExchange("/{id}")
  void release(@PathVariable("id") UUID id);

  /** What the recovery-service asks for. */
  record ReservationRequest(
      UUID reservationId, UUID caseId, String bagTag, String legId, int slots) {}

  /** What the capacity-service answers. */
  record ReservationResponse(UUID reservationId, String status) {}
}
