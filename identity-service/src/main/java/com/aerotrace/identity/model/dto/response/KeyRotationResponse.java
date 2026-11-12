package com.aerotrace.identity.model.dto.response;

/**
 * The outcome of asking for a new signing key.
 *
 * @param kid the key id of the new (or already waiting) key
 */
public record KeyRotationResponse(String kid) {}
