package com.aerotrace.identity.model.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A newly registered account. Deliberately no passenger reference and no password hash.
 *
 * @param id the user id
 * @param username the login name
 * @param email the email address
 * @param fullName the display name
 * @param roles the roles, sorted
 * @param status ACTIVE or DISABLED
 * @param createdAt when the account was created
 */
public record RegisteredUserResponse(
    UUID id,
    String username,
    String email,
    String fullName,
    List<String> roles,
    String status,
    Instant createdAt) {}
