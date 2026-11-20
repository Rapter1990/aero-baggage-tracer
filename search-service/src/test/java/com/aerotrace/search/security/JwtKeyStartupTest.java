package com.aerotrace.search.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.search.config.SearchProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The identity-service public key: never silently the bundled development key (whose private half
 * is in the repository) outside the local, dev and test profiles.
 */
class JwtKeyStartupTest {

  private static SearchProperties properties(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("aerotrace.search", SearchProperties.class);
  }

  private static MockEnvironment profile(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  @DisplayName("no key configured outside a development profile: the service does not start")
  void noKeyOutsideDevelopment() {
    // Empty values, as an unset variable in a compose file gives.
    SearchProperties none =
        properties(
            Map.of(
                "aerotrace.search.jwt.public-key", "",
                "aerotrace.search.jwt.public-key-location", ""));

    assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(none, profile()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IDENTITY_JWT_PUBLIC_KEY");
    assertThatThrownBy(() -> new SecurityConfig().jwtDecoder(none, profile("prod")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(new SecurityConfig().jwtDecoder(none, profile("local"))).isNotNull();
  }

  @Test
  @DisplayName("a configured key is used under any profile")
  void configuredKey() {
    SearchProperties configured =
        properties(
            Map.of("aerotrace.search.jwt.public-key-location", "classpath:test-keys/public.pem"));

    assertThat(new SecurityConfig().jwtDecoder(configured, profile("prod"))).isNotNull();
  }
}
