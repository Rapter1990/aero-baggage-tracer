package com.aerotrace.identity.config;

import com.aerotrace.identity.security.jwt.JwtProperties;
import com.aerotrace.identity.seed.SeedProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SeedProperties.class, JwtProperties.class})
class IdentityConfiguration {

  /** Passwords are stored as salted BCrypt hashes; the cost is raised over time, never lowered. */
  @Bean
  PasswordEncoder passwordEncoder(@Value("${aerotrace.identity.bcrypt-strength:12}") int strength) {
    return new BCryptPasswordEncoder(strength);
  }

  /** The single source of time, so token expiry and the audit trail can be tested with a fake. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
