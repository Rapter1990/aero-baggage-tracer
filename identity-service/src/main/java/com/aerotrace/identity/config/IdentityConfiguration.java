package com.aerotrace.identity.config;

import com.aerotrace.identity.keys.KeyCipher;
import com.aerotrace.identity.keys.KeyProperties;
import com.aerotrace.identity.seed.SeedProperties;
import com.aerotrace.identity.token.TokenProperties;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({SeedProperties.class, KeyProperties.class, TokenProperties.class})
class IdentityConfiguration {

  /** Passwords are stored as salted BCrypt hashes; the cost is raised over time, never lowered. */
  @Bean
  PasswordEncoder passwordEncoder(@Value("${aerotrace.identity.bcrypt-strength:12}") int strength) {
    return new BCryptPasswordEncoder(strength);
  }

  /**
   * The single source of time. It follows the database's clock, so replicas agree on when keys
   * activate and retire even if one machine's own clock is wrong. Tests replace it with a fake.
   */
  @Bean
  Clock clock(EntityManager entityManager) {
    return new DatabaseClock(
        () ->
            (Instant)
                entityManager
                    .createNativeQuery("select clock_timestamp()", Instant.class)
                    .getSingleResult(),
        Clock.systemUTC(),
        Duration.ofMinutes(1));
  }

  /** Encrypts private keys at rest. Start-up fails when the key-encryption key is missing. */
  @Bean
  KeyCipher keyCipher(KeyProperties properties) {
    return KeyCipher.fromBase64(properties.encryptionKey());
  }
}
