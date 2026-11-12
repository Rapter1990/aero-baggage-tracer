package com.aerotrace.identity.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * The answer to a password reset request. It is the same whether or not the account exists. The
 * token and its expiry are only present when the service is told to return them (development).
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
