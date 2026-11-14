package com.aerotrace.identity.config;

import com.aerotrace.identity.auth.AuthProperties;
import com.aerotrace.identity.security.ConcurrencyLimitedPasswordEncoder;
import com.aerotrace.identity.security.jwt.JwtProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@org.springframework.scheduling.annotation.EnableScheduling
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, JwtProperties.class})
class IdentityConfiguration {

  /**
   * Passwords are stored as salted BCrypt hashes; the cost is raised over time, never lowered. At
   * most {@code max-concurrent-hashes} hashes run at once (0 means one per CPU core, at least 2); a
   * call that waits longer than {@code hash-wait} for a slot is refused with a 429.
   */
  @Bean
  PasswordEncoder passwordEncoder(
      @Value("${aerotrace.identity.bcrypt-strength:12}") int strength,
      @Value("${aerotrace.identity.max-concurrent-hashes:0}") int maxConcurrentHashes,
      @Value("${aerotrace.identity.hash-wait:2s}") Duration hashWait,
      MeterRegistry meters) {
    int slots =
        maxConcurrentHashes > 0
            ? maxConcurrentHashes
            : Math.max(2, Runtime.getRuntime().availableProcessors());
    return new ConcurrencyLimitedPasswordEncoder(
        new BCryptPasswordEncoder(strength), slots, hashWait, meters);
  }

  /** The single source of time, so token expiry and the audit trail can be tested with a fake. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
