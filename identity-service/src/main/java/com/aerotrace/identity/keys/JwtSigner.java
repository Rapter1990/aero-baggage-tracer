package com.aerotrace.identity.keys;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

/** Signs claim sets with the current signing key, tagging the token with that key's id. */
@Component
public class JwtSigner {

  private final SigningKeyService keys;

  JwtSigner(SigningKeyService keys) {
    this.keys = keys;
  }

  /** Returns the compact serialization of a JWT signed with the current key. */
  public String sign(JWTClaimsSet claims) {
    SigningKey key = keys.signingKey();
    SigningAlgorithm algorithm = SigningAlgorithm.parse(key.algorithm());
    JWSHeader header =
        new JWSHeader.Builder(algorithm.jws()).keyID(key.kid()).type(JOSEObjectType.JWT).build();
    SignedJWT jwt = new SignedJWT(header, claims);
    try {
      jwt.sign(algorithm.signer(key.privateKey()));
    } catch (JOSEException e) {
      throw new IllegalStateException("Could not sign the token");
    }
    return jwt.serialize();
  }
}
