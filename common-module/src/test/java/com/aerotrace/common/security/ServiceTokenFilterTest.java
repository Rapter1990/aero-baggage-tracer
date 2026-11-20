package com.aerotrace.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ServiceTokenFilterTest {

  private static final String SECRET = "a-long-enough-export-secret";

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private static Authentication call(ServiceTokenFilter filter, String presented) throws Exception {
    // Each call starts unauthenticated, as a request does behind the security context filter.
    SecurityContextHolder.clearContext();
    var request = new MockHttpServletRequest("GET", "/internal/v1/export/cases");
    if (presented != null) {
      request.addHeader(ServiceToken.HEADER, presented);
    }
    AtomicReference<Authentication> seen = new AtomicReference<>();
    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication()));
    return seen.get();
  }

  private static MockEnvironment profiles(String... active) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(active);
    return environment;
  }

  @Test
  @DisplayName("the right token authenticates the call as the service caller")
  void rightTokenAuthenticates() throws Exception {
    var filter = new ServiceTokenFilter(SECRET, profiles());

    Authentication auth = call(filter, SECRET);

    assertThat(filter.mode()).isEqualTo(ServiceToken.Mode.REQUIRED);
    assertThat(auth).isNotNull();
    assertThat(auth.getName()).isEqualTo(ServiceToken.PRINCIPAL);
    assertThat(auth.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_" + ServiceToken.ROLE);
  }

  @Test
  @DisplayName("a wrong or missing token authenticates nothing")
  void wrongOrMissingTokenAuthenticatesNothing() throws Exception {
    var filter = new ServiceTokenFilter(SECRET, profiles());

    assertThat(call(filter, "a-long-enough-export-secreT")).isNull();
    assertThat(call(filter, SECRET + "x")).isNull();
    assertThat(call(filter, "")).isNull();
    assertThat(call(filter, null)).isNull();
  }

  @Test
  @DisplayName("fail closed: without a configured token nothing is accepted outside dev profiles")
  void failsClosedWithoutAToken() throws Exception {
    for (String token : new String[] {null, "", "   "}) {
      for (MockEnvironment env : new MockEnvironment[] {profiles(), profiles("prod", "test")}) {
        var filter = new ServiceTokenFilter(token, env);
        assertThat(filter.mode()).isEqualTo(ServiceToken.Mode.CLOSED);
        assertThat(call(filter, null)).isNull();
        assertThat(call(filter, "")).isNull();
        assertThat(call(filter, "anything-at-all-here")).isNull();
      }
    }
  }

  @Test
  @DisplayName("a token shorter than the minimum counts as not set, so it fails closed")
  void shortTokenFailsClosed() throws Exception {
    var filter = new ServiceTokenFilter("short", profiles());

    assertThat(filter.mode()).isEqualTo(ServiceToken.Mode.CLOSED);
    assertThat(call(filter, "short")).isNull();
  }

  @Test
  @DisplayName("only the local and dev profiles may run without a token, for local runs")
  void devProfilesMayRunOpen() throws Exception {
    for (String profile : new String[] {"local", "dev"}) {
      var filter = new ServiceTokenFilter("", profiles(profile));
      assertThat(filter.mode()).isEqualTo(ServiceToken.Mode.OPEN);
      assertThat(call(filter, null)).isNotNull();
    }
    // A configured token is always required, in dev profiles too.
    var configured = new ServiceTokenFilter(SECRET, profiles("dev"));
    assertThat(configured.mode()).isEqualTo(ServiceToken.Mode.REQUIRED);
    assertThat(call(configured, null)).isNull();
  }
}
