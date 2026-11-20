package com.aerotrace.common.event;

import java.util.List;

/**
 * Payload of {@link Topics#BAG_REGISTERED} (schema revision 1): a newly registered bag and the
 * snapshot of every leg of its itinerary, in leg order. The itinerary never changes after
 * registration. No passenger data: the passenger reference stays in the journey-service.
 */
public record BagRegisteredPayload(String bagTag, List<LegSnapshot> legs) {}
