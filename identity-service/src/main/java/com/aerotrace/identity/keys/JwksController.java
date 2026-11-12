package com.aerotrace.identity.keys;

import com.aerotrace.identity.docs.ProblemExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing keys so the gateway and resource services can verify tokens. */
@RestController
@Tag(name = "Signing keys")
class JwksController {

  private final SigningKeyService keys;

  JwksController(SigningKeyService keys) {
    this.keys = keys;
  }

  @Operation(
      summary = "Public keys for verifying tokens (JWKS)",
      description = "No token needed. Several key ids can be listed while a key is being replaced.",
      security = {})
  @ApiResponse(
      responseCode = "200",
      description = "The JSON Web Key Set. Public keys only.",
      content =
          @Content(
              mediaType = "application/json",
              examples = @ExampleObject(name = "jwks", value = ProblemExamples.JWKS)))
  @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> jwks() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
        .body(keys.publishedKeys().toString());
  }
}
