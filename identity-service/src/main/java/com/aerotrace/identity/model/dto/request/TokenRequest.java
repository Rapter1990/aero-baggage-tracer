package com.aerotrace.identity.model.dto.request;

import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
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
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(max = UserRules.MAX_CREDENTIAL_LENGTH, message = ApiMessages.TOO_LONG)
        String username,
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(max = UserRules.MAX_CREDENTIAL_LENGTH, message = ApiMessages.TOO_LONG)
        String password) {

  @Override
  public String toString() {
    return "TokenRequest[redacted]";
  }
}
