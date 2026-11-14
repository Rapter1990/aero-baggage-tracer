package com.aerotrace.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading an RSA key pair from PEM: inline, from the classpath or a file URL, or from a path. */
class PemKeyUtilsTest {

  private static KeyPair pair;

  @TempDir Path directory;

  @BeforeAll
  static void generate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    pair = generator.generateKeyPair();
  }

  private static String pem(String type, byte[] der) {
    String body =
        Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static String publicPem() {
    return pem("PUBLIC KEY", pair.getPublic().getEncoded());
  }

  private static String privatePem() {
    return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
  }

  @Test
  void readsInlinePemText() {
    PublicKey publicKey = PemKeyUtils.readPublicKey(publicPem(), null);
    PrivateKey privateKey = PemKeyUtils.readPrivateKey(privatePem(), null);

    assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
    assertThat(publicKey.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    assertThat(privateKey.getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
  }

  @Test
  void readsAFileThatIsGivenAsAPlainPathOrAsAFileUrl() throws IOException {
    Path file = directory.resolve("public.pem");
    Files.writeString(file, publicPem(), StandardCharsets.UTF_8);

    PublicKey fromPath = PemKeyUtils.readPublicKey(null, file.toString());
    PublicKey fromUrl = PemKeyUtils.readPublicKey(null, file.toUri().toString());

    assertThat(fromPath.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
    assertThat(fromUrl.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
  }

  @Test
  void readsAClasspathResource() {
    PublicKey publicKey = PemKeyUtils.readPublicKey(null, "classpath:test-keys/public.pem");

    assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
  }

  @Test
  void inlineTextWinsOverALocation() {
    PublicKey publicKey =
        PemKeyUtils.readPublicKey(publicPem(), directory.resolve("nope.pem").toString());

    assertThat(publicKey.getEncoded()).isEqualTo(pair.getPublic().getEncoded());
  }

  @Test
  void toleratesLineBreaksAndIndentationInsideThePem() {
    String squashed = publicPem().replace("\n", "\r\n   ");

    assertThat(PemKeyUtils.readPublicKey(squashed, null).getEncoded())
        .isEqualTo(pair.getPublic().getEncoded());
  }

  @Test
  void saysThatAKeyIsMissing() {
    assertThatThrownBy(() -> PemKeyUtils.readPrivateKey(null, " "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("private key");
    assertThatThrownBy(() -> PemKeyUtils.readPublicKey("", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("public key");
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
