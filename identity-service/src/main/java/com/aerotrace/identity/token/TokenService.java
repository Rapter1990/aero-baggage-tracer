package com.aerotrace.identity.token;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.constant.ApiMessages;
import com.aerotrace.identity.constant.MetricNames;
import com.aerotrace.identity.constant.SecurityConstants;
import com.aerotrace.identity.constant.UserRules;
import com.aerotrace.identity.model.domain.AppUser;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import com.aerotrace.identity.security.jwt.JwtTokenService;
import com.aerotrace.identity.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks credentials and issues a signed access token. A failed sign-in always looks the same from
 * outside (unknown user, wrong password, disabled account), takes the same work, and is audited.
 * Nothing secret is ever logged, counted or put in a span: only the outcome is a metric label.
 */
@Service
@RequiredArgsConstructor
class TokenService {

  private final UserService users;
  private final JwtTokenService jwtTokens;
  private final AuthAuditLog audit;
  private final PasswordEncoder encoder;
  private final MeterRegistry meters;

  /** Checked against when the user does not exist, so that case costs a real hash comparison. */
  @Getter(value = AccessLevel.PRIVATE, lazy = true)
  private final String timingEqualizerHash = encoder.encode(UUID.randomUUID().toString());

  TokenResponse issue(String username, String password) {
    Optional<AppUser> found = users.findByUsername(username);
    boolean authenticated;
    if (found.isEmpty() || !fitsBcrypt(password)) {
      // An unknown user, or a password BCrypt would silently cut short (so "right prefix plus
      // junk" would pass): refuse, after the same amount of work as a real check.
      encoder.matches(withinBcryptLimit(password), getTimingEqualizerHash());
      authenticated = false;
    } else {
      authenticated = users.passwordMatches(found.get(), password) && found.get().enabled();
    }

    if (!authenticated) {
      // The typed username is only kept when it names a real account: people paste passwords and
      // email addresses into that box, and the audit trail can never be cleaned up.
      audit.record(
          AuthEventType.LOGIN_FAILED,
          AuthOutcome.FAILURE,
          found.map(AppUser::username).orElse(null),
          found.map(AppUser::id).orElse(null));
      count(MetricNames.OUTCOME_FAILURE);
      throw new ApiException(ErrorCode.UNAUTHENTICATED, ApiMessages.INVALID_CREDENTIALS);
    }

    AppUser user = found.get();
    String token =
        jwtTokens.generateToken(user.id().toString(), roleNames(user), user.passengerRef());

    audit.record(AuthEventType.TOKEN_ISSUED, AuthOutcome.SUCCESS, user.username(), user.id());
    count(MetricNames.OUTCOME_SUCCESS);
    return new TokenResponse(token, SecurityConstants.TOKEN_TYPE, jwtTokens.ttl().toSeconds());
  }

  private static boolean fitsBcrypt(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length <= UserRules.MAX_PASSWORD_BYTES;
  }

  /** The longest start of the password that BCrypt accepts, cut on a character boundary. */
  private static String withinBcryptLimit(String password) {
    if (fitsBcrypt(password)) {
      return password;
    }
    StringBuilder kept = new StringBuilder();
    int bytes = 0;
    for (int i = 0; i < password.length(); ) {
      int codePoint = password.codePointAt(i);
      String character = new String(Character.toChars(codePoint));
      bytes += character.getBytes(StandardCharsets.UTF_8).length;
      if (bytes > UserRules.MAX_PASSWORD_BYTES) {
        break;
      }
      kept.append(character);
      i += Character.charCount(codePoint);
    }
    return kept.toString();
  }

  private static List<String> roleNames(AppUser user) {
    return user.roles().stream().map(Role::name).sorted().toList();
  }

  private void count(String outcome) {
    meters.counter(MetricNames.TOKEN_REQUESTS, MetricNames.TAG_OUTCOME, outcome).increment();
  }
}
