package com.aerotrace.identity.token;

import com.aerotrace.identity.constant.ApiPaths;
import com.aerotrace.identity.constant.DocsConstants;
import com.aerotrace.identity.constant.SecurityConstants;
import com.aerotrace.identity.docs.ApiExamples;
import com.aerotrace.identity.model.dto.request.TokenRequest;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one unauthenticated API call: exchange a username and password for an access token. The path
 * is the one the api-gateway forwards.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = DocsConstants.TAG_TOKENS)
class TokenController {

  private final TokenService tokens;

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
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                examples = @ExampleObject(name = "issued", value = ApiExamples.TOKEN_ISSUED))),
    @ApiResponse(
        responseCode = "400",
        description = "username or password is missing, blank or too long.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "validation",
                        value = ApiExamples.TOKEN_VALIDATION_ERROR))),
    @ApiResponse(
        responseCode = "401",
        description = "The credentials were not accepted.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "invalid-credentials",
                        value = ApiExamples.TOKEN_INVALID_CREDENTIALS)))
  })
  @PostMapping(
      value = ApiPaths.TOKEN,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<com.aerotrace.common.api.ApiResponse<TokenResponse>> token(
      @Valid @RequestBody TokenRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(SecurityConstants.PRAGMA_HEADER, SecurityConstants.PRAGMA_NO_CACHE)
        .body(
            com.aerotrace.common.api.ApiResponse.success(
                tokens.issue(request.username(), request.password())));
  }
}
