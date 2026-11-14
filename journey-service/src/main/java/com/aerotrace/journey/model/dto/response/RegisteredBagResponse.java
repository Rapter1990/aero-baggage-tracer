package com.aerotrace.journey.model.dto.response;

import java.time.Instant;
import java.util.List;

public record RegisteredBagResponse(String bagTag, Instant registeredAt, List<LegResponse> legs) {}
