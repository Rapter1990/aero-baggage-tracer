package com.aerotrace.identity.model.dto.request;

import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Starts a password reset.
 *
 * @param usernameOrEmail the account username or email address
 */
public record ForgotPasswordRequest(
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(max = UserRules.MAX_CREDENTIAL_LENGTH, message = ApiMessages.TOO_LONG)
        String usernameOrEmail) {

  @Override
  public String toString() {
    return "ForgotPasswordRequest[redacted]";
  }
}
