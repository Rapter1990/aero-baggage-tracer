package com.aerotrace.search.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.search.search.CursorCodec;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * What the service refuses to start without outside the development profiles (local, dev, test): a
 * cursor secret of at least 32 bytes and a named Elasticsearch user. A short cursor secret is
 * refused under every profile. (The public key check is in {@code JwtKeyStartupTest}.)
 */
class StartupChecksTest {

  private static SearchProperties properties(String cursorSecret) {
    Map<String, String> values = new HashMap<>();
    if (cursorSecret != null) {
      values.put("aerotrace.search.cursor.secret", cursorSecret);
    }
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("aerotrace.search", SearchProperties.class);
  }

  private static MockEnvironment profile(String... profiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    return environment;
  }

  @Test
  @DisplayName("the cursor secret is required outside the development profiles")
  void cursorSecretRequired() {
    assertThatThrownBy(() -> new CursorCodec(properties(null), Clock.systemUTC(), profile("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEARCH_CURSOR_SECRET is not set");
    assertThatThrownBy(() -> new CursorCodec(properties(""), Clock.systemUTC(), profile()))
        .isInstanceOf(IllegalStateException.class);
    for (String development : Profiles.DEVELOPMENT) {
      assertThatCode(() -> new CursorCodec(properties(""), Clock.systemUTC(), profile(development)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("a cursor secret shorter than 32 bytes is refused under every profile")
  void cursorSecretLength() {
    String shortSecret = "x".repeat(31);
    for (String any : new String[] {"prod", "test"}) {
      assertThatThrownBy(
              () -> new CursorCodec(properties(shortSecret), Clock.systemUTC(), profile(any)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("at least 32 bytes")
          .hasMessageNotContaining(shortSecret);
    }
    assertThatCode(
            () -> new CursorCodec(properties("x".repeat(32)), Clock.systemUTC(), profile("prod")))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the Elasticsearch user must be named outside the development profiles")
  void elasticsearchUserRequired() {
    ElasticsearchClientConfiguration configuration = new ElasticsearchClientConfiguration();
    Duration t = Duration.ofSeconds(1);

    assertThatThrownBy(() -> configuration.elasticsearchTimeouts(t, t, "", profile("prod")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ELASTICSEARCH_USERNAME");
    assertThat(configuration.elasticsearchTimeouts(t, t, "", profile("dev"))).isNotNull();
    assertThat(configuration.elasticsearchTimeouts(t, t, "search-service", profile("prod")))
        .isNotNull();
  }
}
