package com.aerotrace.identity.model.dto.request;

import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asks for a new access token in exchange for a refresh token, which is then used up.
 *
 * @param refreshToken the refresh token from the last sign-in or refresh
 */
public record RefreshTokenRequest(
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(max = UserRules.MAX_CREDENTIAL_LENGTH, message = ApiMessages.TOO_LONG)
        String refreshToken) {

  @Override
  public String toString() {
    return "RefreshTokenRequest[redacted]";
  }
}
