package com.aerotrace.identity.config;

import com.aerotrace.identity.keys.KeyCipher;
import com.aerotrace.identity.keys.KeyProperties;
import com.aerotrace.identity.seed.SeedProperties;
import com.aerotrace.identity.token.TokenProperties;
import java.time.Clock;
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

  /** The single source of time, so key rotation and token expiry can be tested with a fake one. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** Encrypts private keys at rest. Start-up fails when the key-encryption key is missing. */
  @Bean
  KeyCipher keyCipher(KeyProperties properties) {
    return KeyCipher.fromBase64(properties.encryptionKey());
  }
}
