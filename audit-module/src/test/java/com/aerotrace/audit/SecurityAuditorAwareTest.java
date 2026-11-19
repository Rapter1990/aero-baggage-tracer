package com.aerotrace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SecurityAuditorAwareTest {

  private final SecurityAuditorAware auditor = new SecurityAuditorAware();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("a signed-in user is audited by the subject of the token")
  void jwtSubject() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("agent-1").build();
    // As the resource server builds it: with the (here empty) authorities, which marks it signed
    // in.
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

    assertThat(auditor.getCurrentAuditor()).contains("agent-1");
  }

  @Test
  @DisplayName("a service with the service token is audited as that service")
  void serviceCaller() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                "service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));

    assertThat(auditor.getCurrentAuditor()).contains("service");
  }

  @Test
  @DisplayName("a request without credentials is anonymous")
  void anonymousRequest() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

    assertThat(auditor.getCurrentAuditor()).contains(SecurityAuditorAware.ANONYMOUS);
  }

  @Test
  @DisplayName("work with no request at all (a job, a consumer) is done by the system")
  void noAuthentication() {
    assertThat(auditor.getCurrentAuditor()).contains(SecurityAuditorAware.SYSTEM);
    assertThat(SecurityAuditorAware.currentAuditor()).isEqualTo("system");
  }

  @Test
  @DisplayName("a very long name is cut to the width of the audit column")
  void longNameIsCut() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("x".repeat(500), null, List.of()));

    assertThat(SecurityAuditorAware.currentAuditor()).hasSize(SecurityAuditorAware.MAX_LENGTH);
  }

  @Test
  @DisplayName("a blank name falls back to the system")
  void blankNameFallsBack() {
    SecurityContextHolder.getContext()
        .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(" ", null, List.of()));

    assertThat(SecurityAuditorAware.currentAuditor()).isEqualTo(SecurityAuditorAware.SYSTEM);
  }
}
