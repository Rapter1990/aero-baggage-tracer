package com.aerotrace.identity.security.jwt;

import com.aerotrace.identity.constant.ApiPaths;
import com.aerotrace.identity.constant.DocsConstants;
import com.aerotrace.identity.constant.SecurityConstants;
import com.aerotrace.identity.docs.ApiExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public signing key so the gateway and resource services can verify tokens. */
@RestController
@RequiredArgsConstructor
@Tag(name = DocsConstants.TAG_SIGNING_KEYS)
class JwksController {

  private final JwtTokenService tokens;

  @Operation(
      summary = "Public key for verifying tokens (JWKS)",
      description = "No token needed. The key set holds the public key only.",
      security = {})
  @ApiResponse(
      responseCode = "200",
      description = "The JSON Web Key Set. Public key only.",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              examples = @ExampleObject(name = "jwks", value = ApiExamples.JWKS)))
  @GetMapping(value = ApiPaths.JWKS, produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> jwks() {
    return ResponseEntity.ok()
        .cacheControl(
            CacheControl.maxAge(Duration.ofSeconds(SecurityConstants.JWKS_MAX_AGE_SECONDS))
                .cachePublic())
        .body(tokens.jwksJson());
  }
}
