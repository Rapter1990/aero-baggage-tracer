package com.aerotrace.identity.token;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one unauthenticated API call: exchange a username and password for an access token. The path
 * is the one the api-gateway forwards.
 */
@RestController
@RequestMapping("/api/v1/auth")
class TokenController {

  /** Generous upper bound that stops oversized credentials before they reach the hash function. */
  private static final int MAX_LENGTH = 256;

  private final TokenService tokens;

  TokenController(TokenService tokens) {
    this.tokens = tokens;
  }

  @PostMapping(
      value = "/token",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<TokenResponse> token(@RequestBody TokenRequest request) {
    if (blank(request.username()) || blank(request.password())) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "username and password are required");
    }
    if (request.username().length() > MAX_LENGTH || request.password().length() > MAX_LENGTH) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "username or password is too long");
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(tokens.issue(request.username(), request.password()));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
