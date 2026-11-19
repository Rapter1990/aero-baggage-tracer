package com.aerotrace.capacity.model.dto;

import java.util.UUID;

/** A hold and its state: HELD, CONFIRMED, RELEASED or EXPIRED. */
public record ReservationResponse(UUID reservationId, String status) {}
