package com.aerotrace.identity.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.identity.audit.AuthAuditLog;
import com.aerotrace.identity.keys.JwtSigner;
import com.aerotrace.identity.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The parts of sign-in that are hard to see from outside. */
class TokenServiceTest {

  private static final String DUMMY_HASH =
      "$2a$04$dummydummydummydummydummydummydummydummydummydummydummy";

  private final UserService users = mock(UserService.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);

  private TokenService service(String issuer) {
    when(encoder.encode(any())).thenReturn(DUMMY_HASH);
    return new TokenService(
        users,
        mock(JwtSigner.class),
        mock(AuthAuditLog.class),
        encoder,
        Clock.systemUTC(),
        new TokenProperties(issuer, "aerotrace-api", Duration.ofMinutes(15), Duration.ofMinutes(1)),
        new SimpleMeterRegistry());
  }

  @Test
  void anUnknownUserStillCostsAPasswordHashCheck() {
    TokenService service = service("http://identity.test");
    when(users.findByUsername("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.issue("ghost", "some-password-1"))
        .isInstanceOf(ApiException.class);

    // Same work as for a real user, so response time does not reveal which usernames exist.
    verify(encoder).matches("some-password-1", DUMMY_HASH);
  }

  @Test
  void refusesToStartWithoutAnIssuer() {
    assertThatThrownBy(() -> service(null)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service(" ")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theRequestNeverPrintsThePassword() {
    assertThat(new TokenRequest("agent", "super-secret-password").toString())
        .doesNotContain("super-secret-password");
  }

  @Test
  void theResponseNeverPrintsTheToken() {
    assertThat(new TokenResponse("header.payload.signature", "Bearer", 900).toString())
        .doesNotContain("header.payload.signature");
  }
}
