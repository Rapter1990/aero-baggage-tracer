package com.aerotrace.identity.model.dto.request;

import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ends a session by revoking its refresh token.
 *
 * @param refreshToken the refresh token to revoke
 */
public record LogoutRequest(
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(max = UserRules.MAX_TOKEN_LENGTH, message = ApiMessages.TOKEN_TOO_LONG)
        String refreshToken) {

  @Override
  public String toString() {
    return "LogoutRequest[redacted]";
  }
}
