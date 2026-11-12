package com.aerotrace.identity.token;

import com.aerotrace.common.error.ApiException;
import com.aerotrace.common.error.ErrorCode;
import com.aerotrace.identity.docs.ApiExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Tokens")
class TokenController {

  /** Generous upper bound that stops oversized credentials before they reach the hash function. */
  private static final int MAX_LENGTH = 256;

  private final TokenService tokens;

  TokenController(TokenService tokens) {
    this.tokens = tokens;
  }

  @Operation(
      summary = "Exchange a username and password for an access token",
      description =
          "The one call that needs no token. An unknown user, a wrong password and a disabled "
              + "account all get the same 401, so the answer reveals nothing about which usernames exist.",
      security = {})
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Signed access token. Send it as `Authorization: Bearer <token>`.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = TokenResponse.class),
                examples = @ExampleObject(name = "issued", value = ApiExamples.TOKEN_ISSUED))),
    @ApiResponse(
        responseCode = "400",
        description = "username or password is missing, blank or too long.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(ref = "#/components/schemas/ApiError"),
                examples =
                    @ExampleObject(
                        name = "validation",
                        value = ApiExamples.TOKEN_VALIDATION_ERROR))),
    @ApiResponse(
        responseCode = "401",
        description = "The credentials were not accepted.",
        content =
            @Content(
                mediaType = "application/json",
                schema = @Schema(ref = "#/components/schemas/ApiError"),
                examples =
                    @ExampleObject(
                        name = "invalid-credentials",
                        value = ApiExamples.TOKEN_INVALID_CREDENTIALS)))
  })
  @PostMapping(
      value = "/token",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<TokenResponse> token(@RequestBody TokenRequest request) {
    if (blank(request.username()) || blank(request.password())) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "username and password are required");
    }
    if (request.username().length() > MAX_LENGTH || request.password().length() > MAX_LENGTH) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "username or password is too long");
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
