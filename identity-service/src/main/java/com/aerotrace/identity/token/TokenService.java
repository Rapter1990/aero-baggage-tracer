package com.aerotrace.identity.token;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.common.security.JwtClaims;
import com.aerotrace.common.security.Role;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.audit.AuthEventType;
import com.aerotrace.identity.audit.AuthOutcome;
import com.aerotrace.identity.keys.JwtSigner;
import com.aerotrace.identity.user.UserAccount;
import com.aerotrace.identity.user.UserService;
import com.nimbusds.jwt.JWTClaimsSet;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Checks credentials and issues a signed access token. A failed sign-in always looks the same from
 * outside (unknown user, wrong password, disabled account), takes the same work, and is audited.
 * Nothing secret is ever logged, counted or put in a span: only the outcome is a metric label.
 */
@Service
class TokenService {

  private static final String INVALID_CREDENTIALS = "Invalid credentials.";

  private final UserService users;
  private final JwtSigner signer;
  private final AuthAuditLog audit;
  private final PasswordEncoder encoder;
  private final Clock clock;
  private final TokenProperties properties;
  private final MeterRegistry meters;

  /** Checked against when the user does not exist, so that case costs a real hash comparison. */
  private final String timingEqualizerHash;

  TokenService(
      UserService users,
      JwtSigner signer,
      AuthAuditLog audit,
      PasswordEncoder encoder,
      Clock clock,
      TokenProperties properties,
      MeterRegistry meters) {
    if (properties.issuer() == null || properties.issuer().isBlank()) {
      throw new IllegalStateException("aerotrace.identity.token.issuer must be set");
    }
    this.users = users;
    this.signer = signer;
    this.audit = audit;
    this.encoder = encoder;
    this.clock = clock;
    this.properties = properties;
    this.meters = meters;
    this.timingEqualizerHash = encoder.encode(UUID.randomUUID().toString());
  }

  TokenResponse issue(String username, String password) {
    Optional<UserAccount> found = users.findByUsername(username);
    boolean authenticated;
    if (found.isEmpty()) {
      encoder.matches(password, timingEqualizerHash);
      authenticated = false;
    } else {
      authenticated = users.passwordMatches(found.get(), password) && found.get().enabled();
    }

    if (!authenticated) {
      audit.record(
          AuthEventType.LOGIN_FAILED,
          AuthOutcome.FAILURE,
          username,
          found.map(UserAccount::id).orElse(null));
      count("failure");
      throw new ApiException(ErrorCode.UNAUTHENTICATED, INVALID_CREDENTIALS);
    }

    UserAccount user = found.get();
    Instant now = clock.instant();
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(properties.issuer())
            .audience(properties.audience())
            .subject(user.id().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(properties.ttl())))
            .jwtID(UUID.randomUUID().toString())
            .claim(JwtClaims.ROLES, roleNames(user));
    if (user.passengerRef() != null) {
      claims.claim(JwtClaims.PASSENGER_REF, user.passengerRef());
    }
    String token = signer.sign(claims.build());

    audit.record(AuthEventType.TOKEN_ISSUED, AuthOutcome.SUCCESS, user.username(), user.id());
    count("success");
    return new TokenResponse(token, "Bearer", properties.ttl().toSeconds());
  }

  private static List<String> roleNames(UserAccount user) {
    return user.roles().stream().map(Role::name).sorted().toList();
  }

  private void count(String outcome) {
    meters.counter("identity.auth.token.requests", "outcome", outcome).increment();
  }
}
