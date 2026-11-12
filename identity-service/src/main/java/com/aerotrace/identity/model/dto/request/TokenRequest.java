package com.aerotrace.identity.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials posted to the token endpoint. The limit keeps oversized input away from the password
 * hash. Never printed, so a log line cannot leak them.
 *
 * @param username the account name; matched case-insensitively
 * @param password the account password
 */
public record TokenRequest(
    @NotBlank(message = "must not be blank")
        @Size(max = 256, message = "must be at most 256 characters")
        String username,
    @NotBlank(message = "must not be blank")
        @Size(max = 256, message = "must be at most 256 characters")
        String password) {

  @Override
  public String toString() {
    return "TokenRequest[redacted]";
  }
}
