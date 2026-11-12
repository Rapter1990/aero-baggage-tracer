package com.aerotrace.identity.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * A token-signing key. Several are live at once: the most recently activated one signs, and a
 * replaced one stays published until the last token it signed has expired ({@code retireAt}).
 *
 * <p>The private key is only ever stored encrypted, and is erased when the key retires. The key id
 * is assigned by the application (the RFC 7638 thumbprint of the public key).
 */
@Entity
@Table(name = "signing_key", schema = "identity")
public class SigningKeyEntity implements Persistable<String> {

  @Id
  @Column(length = 64)
  private String kid;

  @Column(nullable = false, length = 5)
  private String algorithm;

  /** X.509 SubjectPublicKeyInfo, base64. */
  @Column(name = "public_key", nullable = false)
  private String publicKey;

  /** Versioned AES-GCM ciphertext of the PKCS#8 private key; null once the key is retired. */
  @Column(name = "private_key_enc")
  private String privateKeyEnc;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "activate_at", nullable = false)
  private Instant activateAt;

  @Column(name = "retire_at")
  private Instant retireAt;

  @Transient private boolean isNew = true;

  protected SigningKeyEntity() {}

  public SigningKeyEntity(
      String kid,
      String algorithm,
      String publicKey,
      String privateKeyEnc,
      Instant createdAt,
      Instant activateAt) {
    this.kid = kid;
    this.algorithm = algorithm;
    this.publicKey = publicKey;
    this.privateKeyEnc = privateKeyEnc;
    this.createdAt = createdAt;
    this.activateAt = activateAt;
  }

  @PostLoad
  @PostPersist
  void markStored() {
    isNew = false;
  }

  @Override
  public String getId() {
    return kid;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  public String getKid() {
    return kid;
  }

  public String getAlgorithm() {
    return algorithm;
  }

  public String getPublicKey() {
    return publicKey;
  }

  public String getPrivateKeyEnc() {
    return privateKeyEnc;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getActivateAt() {
    return activateAt;
  }

  public Instant getRetireAt() {
    return retireAt;
  }

  @Override
  public String toString() {
    return "SigningKeyEntity[kid=" + kid + ", algorithm=" + algorithm + "]";
  }
}
