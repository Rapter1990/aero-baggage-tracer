package com.aerotrace.identity.model.dto.request;

import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sets a new password with a reset token.
 *
 * @param resetToken the token from the password reset
 * @param newPassword the new password, at least 12 characters and at most 72 bytes
 */
public record ResetPasswordRequest(
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(max = UserRules.MAX_CREDENTIAL_LENGTH, message = ApiMessages.TOO_LONG)
        String resetToken,
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(
            min = UserRules.MIN_PASSWORD_LENGTH,
            max = UserRules.MAX_PASSWORD_BYTES,
            message = ApiMessages.PASSWORD_RULE)
        String newPassword) {

  @Override
  public String toString() {
    return "ResetPasswordRequest[redacted]";
  }
}
