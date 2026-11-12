package com.aerotrace.identity.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.aerotrace.identity.base.AbstractBaseServiceTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenHashUtilsTest extends AbstractBaseServiceTest {

  @Test
  void shouldGenerateUrlSafeTokensOfTheSameLength() {
    // When
    String token = TokenHashUtils.randomToken();

    // Then: 48 bytes is 64 base64url characters without padding
    assertThat(token).hasSize(64).matches("[A-Za-z0-9_-]+");
  }

  @Test
  void shouldNeverGenerateTheSameTokenTwice() {
    // When
    Set<String> tokens = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      tokens.add(TokenHashUtils.randomToken());
    }

    // Then
    assertThat(tokens).hasSize(1000);
  }

  @Test
  void shouldHashToSixtyFourHexCharactersDeterministically() {
    // When
    String first = TokenHashUtils.sha256("a-token");
    String second = TokenHashUtils.sha256("a-token");

    // Then
    assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]+");
    assertThat(TokenHashUtils.sha256("another-token")).isNotEqualTo(first);
  }

  @Test
  void shouldMatchTheKnownSha256OfAnEmptyString() {
    assertThat(TokenHashUtils.sha256(""))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Test
  void shouldNotContainTheTokenInItsHash() {
    String token = TokenHashUtils.randomToken();

    assertThat(TokenHashUtils.sha256(token)).doesNotContain(token);
  }
}
