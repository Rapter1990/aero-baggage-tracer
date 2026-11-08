package com.aerotrace.common.event;

/** Payload of {@link Topics#DEAD_LETTER}. The original payload is kept as raw JSON text. */
public record DeadLetterPayload(
    String originalTopic, String originalEventId, String reason, int attempts, String rawPayload) {}
