package com.aerotrace.identity.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aerotrace.identity.support.TestKeys;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the RSA key pair from PEM: inline, from the classpath, or from a file. */
class PemKeyUtilsTest {

  @TempDir Path directory;

  @Test
  void readsTheBundledPairFromTheClasspath() {
    PublicKey publicKey = PemKeyUtils.readPublicKey(null, "classpath:keys/public.pem");
    PrivateKey privateKey = PemKeyUtils.readPrivateKey(null, "classpath:keys/private.pem");

    assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
    assertThat(publicKey.getEncoded()).isEqualTo(TestKeys.bundledPublicKey().getEncoded());
    assertThat(privateKey.getEncoded()).isEqualTo(TestKeys.bundledPrivateKey().getEncoded());
  }

  @Test
  void readsInlinePemText() {
    KeyPair pair = TestKeys.generate();

    PublicKey publicKey = PemKeyUtils.readPublicKey(TestKeys.publicPem(pair.getPublic()), null);
    PrivateKey privateKey =
        PemKeyUtils.readPrivateKey(TestKeys.privatePem(pair.getPrivate()), null);

    assertThat(publicKey.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    assertThat(privateKey.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
  }

  @Test
  void readsAFileThatIsGivenAsAPlainPathOrAsAFileUrl() throws IOException {
    KeyPair pair = TestKeys.generate();
    Path file = directory.resolve("private.pem");
    Files.writeString(file, TestKeys.privatePem(pair.getPrivate()), StandardCharsets.UTF_8);

    PrivateKey fromPath = PemKeyUtils.readPrivateKey(null, file.toString());
    PrivateKey fromUrl = PemKeyUtils.readPrivateKey(null, file.toUri().toString());

    assertThat(fromPath.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    assertThat(fromUrl.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
  }

  @Test
  void inlineTextWinsOverALocation() {
    KeyPair inline = TestKeys.generate();

    PublicKey publicKey =
        PemKeyUtils.readPublicKey(
            TestKeys.publicPem(inline.getPublic()), "classpath:keys/public.pem");

    assertThat(publicKey.getEncoded()).isEqualTo(inline.getPublic().getEncoded());
  }

  @Test
  void toleratesLineBreaksAndIndentationInsideThePem() {
    String squashed = TestKeys.publicPem(TestKeys.bundledPublicKey()).replace("\n", "\r\n   ");

    assertThat(PemKeyUtils.readPublicKey(squashed, null).getEncoded())
        .isEqualTo(TestKeys.bundledPublicKey().getEncoded());
  }

  @Test
  void saysWhichSettingsAreMissing() {
    assertThatThrownBy(() -> PemKeyUtils.readPrivateKey(null, " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("private key")
        .hasMessageContaining("aerotrace.identity.jwt.private-key");
    assertThatThrownBy(() -> PemKeyUtils.readPublicKey("", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("aerotrace.identity.jwt.public-key");
  }

  @Test
  void refusesAPrivateKeyThatIsNotPkcs8() {
    // The older "BEGIN RSA PRIVATE KEY" form (PKCS#1) is not accepted.
    String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n";

    assertThatThrownBy(() -> PemKeyUtils.readPrivateKey(pkcs1, null))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void refusesTextThatIsNotAKey() {
    assertThatThrownBy(
            () ->
                PemKeyUtils.readPublicKey(
                    "-----BEGIN PUBLIC KEY-----\nQUJD\n-----END PUBLIC KEY-----", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot parse RSA public key");
    assertThatThrownBy(
            () ->
                PemKeyUtils.readPrivateKey(
                    "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PKCS#8");
  }

  @Test
  void aLocationThatDoesNotExistIsReportedWithoutItsContents() {
    assertThatThrownBy(
            () -> PemKeyUtils.readPrivateKey(null, directory.resolve("nope.pem").toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Cannot read JWT private key");
  }
}
