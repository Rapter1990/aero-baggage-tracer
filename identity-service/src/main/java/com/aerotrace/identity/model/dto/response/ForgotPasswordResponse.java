package com.aerotrace.identity.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The answer to a password reset request. The message is the same whether or not the account
 * exists, but only an existing, enabled account gets a token and an expiry. The token is returned
 * to the caller because there is no mail service yet: see the README's known limitations.
 *
 * @param message a fixed text
 * @param resetToken the reset token, or absent
 * @param expiresAt when the token expires, or absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForgotPasswordResponse(String message, String resetToken, Instant expiresAt) {

  @Override
  public String toString() {
    return "ForgotPasswordResponse[message=" + message + "]";
  }
}
