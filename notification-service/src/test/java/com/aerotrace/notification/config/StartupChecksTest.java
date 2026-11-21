package com.aerotrace.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * What the service refuses to start without. The settings are bound when the context starts, so a
 * missing or unsafe value stops the start-up instead of surfacing later.
 */
class StartupChecksTest {

  private static final String DEV_KEY =
      "aerotrace.notification.jwt.public-key-location=classpath:keys/public.pem";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(NotificationConfiguration.class);

  @Test
  @DisplayName("the service refuses to start without an identity public key")
  void startupFailsWithoutAPublicKey() {
    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .hasMessageContaining("IDENTITY_JWT_PUBLIC_KEY")
              .hasMessageContaining("IDENTITY_JWT_PUBLIC_KEY_LOCATION");
        });
  }

  @Test
  @DisplayName("an empty key setting (an unset variable in a compose file) counts as no key")
  void blankKeySettingsCountAsNoKey() {
    runner
        .withPropertyValues(
            "aerotrace.notification.jwt.public-key=",
            "aerotrace.notification.jwt.public-key-location= ")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("the service starts with a key, inline or from a location")
  void startsWithAKey() {
    runner.withPropertyValues(DEV_KEY).run(context -> assertThat(context).hasNotFailed());
    runner
        .withPropertyValues(
            "aerotrace.notification.jwt.public-key=-----BEGIN PUBLIC KEY-----x-----END PUBLIC KEY-----")
        .run(context -> assertThat(context).hasNotFailed());
  }
}
