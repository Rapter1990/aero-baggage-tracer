package com.aerotrace.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for identity-service: serves a JWKS over HTTP (so the gateway's real JWKS code path is
 * exercised) and mints tokens signed by the published keys.
 */
public final class TestJwks {

  public static final String ISSUER = "https://identity.test";
  public static final String AUDIENCE = "aerotrace-api";

  private static final Map<String, RSAKey> PUBLISHED = new ConcurrentHashMap<>();
  private static final HttpServer SERVER;

  static {
    try {
      SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      SERVER.createContext(
          "/jwks",
          exchange -> {
            List<JWK> keys = List.copyOf(PUBLISHED.values());
            byte[] body = new JWKSet(keys).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      SERVER.start();
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private TestJwks() {}

  public static String uri() {
    return "http://localhost:" + SERVER.getAddress().getPort() + "/jwks";
  }

  /** Generates a key and publishes it, like a key rotation adding a new active kid. */
  public static RSAKey publish(String kid) {
    RSAKey key = generate(kid);
    PUBLISHED.put(kid, key);
    return key;
  }

  /** A key that is never published, to model a forged or foreign signature. */
  public static RSAKey unpublished(String kid) {
    return generate(kid);
  }

  private static RSAKey generate(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static JWTClaimsSet.Builder claims(String subject, List<String> roles) {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject(subject)
        .claim("roles", roles)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)));
  }

  public static String sign(RSAKey key, JWTClaimsSet claims) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String unsigned(JWTClaimsSet claims) {
    return new PlainJWT(claims).serialize();
  }
}
