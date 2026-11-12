package com.aerotrace.identity.keys;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing keys so the gateway and resource services can verify tokens. */
@RestController
class JwksController {

  private final SigningKeyService keys;

  JwksController(SigningKeyService keys) {
    this.keys = keys;
  }

  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
        .body(keys.publishedKeys().toString());
  }
}
