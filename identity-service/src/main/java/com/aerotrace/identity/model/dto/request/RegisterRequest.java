package com.aerotrace.identity.model.dto.request;

import com.aerotrace.common.security.Role;
import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.UserRules;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration of a new account.
 *
 * <p>Anyone can register, and only as a passenger. Asking for any other role needs an ADMIN access
 * token. The passenger reference is never taken from the request: it is generated.
 *
 * @param username the login name; stored in lower case
 * @param email the email address; stored in lower case
 * @param password the password, at least 12 characters and at most 72 bytes
 * @param fullName the display name
 * @param role the role to give the account; omitted means PASSENGER
 */
public record RegisterRequest(
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Pattern(regexp = UserRules.USERNAME_PATTERN, message = ApiMessages.USERNAME_RULE)
        String username,
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Email(message = ApiMessages.MUST_BE_EMAIL)
        @Size(max = UserRules.MAX_EMAIL_LENGTH, message = ApiMessages.TOO_LONG)
        String email,
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(
            min = UserRules.MIN_PASSWORD_LENGTH,
            max = UserRules.MAX_PASSWORD_BYTES,
            message = ApiMessages.PASSWORD_RULE)
        String password,
    @NotBlank(message = ApiMessages.MUST_NOT_BE_BLANK)
        @Size(
            min = UserRules.MIN_FULL_NAME_LENGTH,
            max = UserRules.MAX_FULL_NAME_LENGTH,
            message = ApiMessages.FULL_NAME_RULE)
        String fullName,
    Role role) {

  @Override
  public String toString() {
    return "RegisterRequest[redacted]";
  }
}
