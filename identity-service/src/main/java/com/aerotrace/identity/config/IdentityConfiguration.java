package com.aerotrace.identity.config;

import com.aerotrace.identity.keys.KeyCipher;
import com.aerotrace.identity.keys.KeyProperties;
import com.aerotrace.identity.seed.SeedProperties;
import com.aerotrace.identity.token.TokenProperties;
import com.aerotrace.identity.token.TokenTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Query;
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
   * The time used for key lifecycle decisions and the audit trail. It follows the database's clock,
   * so replicas agree on when keys activate and retire even if one machine's own clock is wrong.
   * Tests replace it with a fake.
   */
  @Bean
  Clock clock(EntityManagerFactory entityManagerFactory) {
    return new DatabaseClock(
        () -> databaseNow(entityManagerFactory), Clock.systemUTC(), Duration.ofMinutes(1));
  }

  /**
   * Token {@code iat} and {@code exp} are judged by the gateway and every resource server with
   * their own clocks, so they are stamped with this machine's clock, not the database's.
   */
  @Bean
  TokenTime tokenTime() {
    return new TokenTime(Clock.systemUTC());
  }

  /**
   * Asks the database for its time on a connection of its own, so a failure or a slow answer can
   * never mark a transaction of the caller rollback-only, and gives up quickly.
   */
  private static Instant databaseNow(EntityManagerFactory entityManagerFactory) {
    try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
      Query query = entityManager.createNativeQuery("select clock_timestamp()", Instant.class);
      query.setHint("jakarta.persistence.query.timeout", 2000);
      return (Instant) query.getSingleResult();
    }
  }

  /** Encrypts private keys at rest. Start-up fails when the key-encryption key is missing. */
  @Bean
  KeyCipher keyCipher(KeyProperties properties) {
    return KeyCipher.fromBase64(properties.encryptionKey());
  }
}
