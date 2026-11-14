package com.aerotrace.identity.auth;

import com.aerotrace.common.api.ApiResponse;
import com.aerotrace.common.security.SecurityContextHelper;
import com.aerotrace.identity.constant.ApiPaths;
import com.aerotrace.identity.constant.DocsConstants;
import com.aerotrace.identity.constant.SecurityConstants;
import com.aerotrace.identity.docs.ApiExamples;
import com.aerotrace.identity.model.dto.request.ForgotPasswordRequest;
import com.aerotrace.identity.model.dto.request.LoginRequest;
import com.aerotrace.identity.model.dto.request.LogoutRequest;
import com.aerotrace.identity.model.dto.request.RefreshTokenRequest;
import com.aerotrace.identity.model.dto.request.RegisterRequest;
import com.aerotrace.identity.model.dto.request.ResetPasswordRequest;
import com.aerotrace.identity.model.dto.response.ForgotPasswordResponse;
import com.aerotrace.identity.model.dto.response.LogoutResponse;
import com.aerotrace.identity.model.dto.response.RegisteredUserResponse;
import com.aerotrace.identity.model.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authentication API: register, login, refresh-token, forgot-password, reset-password and
 * logout. Everything here except registration of a staff account is called without an access token.
 * Every response is marked as not to be cached, because it can carry a token.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = DocsConstants.TAG_AUTHENTICATION)
class AuthController {

  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

  private final AuthService auth;

  @Operation(
      summary = "Register an account",
      description =
          "Anyone can register a passenger account. A staff role (AGENT, DISPATCHER, ADMIN) needs "
              + "an ADMIN access token (send it as a bearer token); without one the answer is 401, "
              + "with another role 403. A passenger registration needs no token.",
      security = @SecurityRequirement(name = DocsConstants.BEARER_AUTH))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "The account was created.",
        content =
            @Content(
                mediaType = JSON,
                examples = @ExampleObject(name = "registered", value = ApiExamples.REGISTERED))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "A field is missing or breaks a rule.",
        content =
            @Content(
                mediaType = JSON,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "validation",
                        value = ApiExamples.REGISTER_VALIDATION_ERROR))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "The username or email is already registered.",
        content =
            @Content(
                mediaType = JSON,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(name = "exists", value = ApiExamples.USER_ALREADY_EXISTS)))
  })
  @PostMapping(value = ApiPaths.REGISTER, consumes = JSON, produces = JSON)
  ResponseEntity<ApiResponse<RegisteredUserResponse>> register(
      @Valid @RequestBody RegisterRequest request) {
    AuthService.Caller caller =
        SecurityContextHelper.currentSubject().isPresent()
            ? new AuthService.Caller(true, SecurityContextHelper.currentRoles())
            : AuthService.Caller.anonymous();
    return noStore(HttpStatus.CREATED).body(ApiResponse.success(auth.register(request, caller)));
  }

  @Operation(
      summary = "Sign in with a username or email and a password",
      description =
          "An unknown user, a wrong password and a disabled account all get the same 401, so the "
              + "answer reveals nothing about which accounts exist.",
      security = {})
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description =
            "An access token and a refresh token. Send the access token as "
                + "`Authorization: Bearer <token>`.",
        content =
            @Content(
                mediaType = JSON,
                examples = @ExampleObject(name = "issued", value = ApiExamples.TOKEN_ISSUED))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "A field is missing, blank or too long.",
        content =
            @Content(
                mediaType = JSON,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "validation",
                        value = ApiExamples.LOGIN_VALIDATION_ERROR))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "The credentials were not accepted.",
        content =
            @Content(
                mediaType = JSON,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "invalid-credentials",
                        value = ApiExamples.INVALID_CREDENTIALS)))
  })
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "429",
      description =
          "Too many wrong passwords for that name: it is locked for a limited time, even for the "
              + "right password, and the answer is the same whether or not the account exists. "
              + "`Retry-After` says how many seconds to wait. Also sent when the service is busy "
              + "hashing passwords.",
      content = @Content(mediaType = JSON, schema = @Schema(ref = DocsConstants.API_ERROR_REF)))
  @PostMapping(value = ApiPaths.LOGIN, consumes = JSON, produces = JSON)
  ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
    return noStore(HttpStatus.OK).body(ApiResponse.success(auth.login(request)));
  }

  @Operation(
      summary = "Exchange a refresh token for a new access token and refresh token",
      description =
          "A refresh token works once. Sending one a second time is treated as theft and ends "
              + "every session of that user.",
      security = {})
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "A new token pair; the old refresh token is no longer valid."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "The refresh token is unknown, expired, revoked or already used.",
        content =
            @Content(
                mediaType = JSON,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "invalid-refresh-token",
                        value = ApiExamples.INVALID_REFRESH_TOKEN)))
  })
  @PostMapping(value = ApiPaths.REFRESH_TOKEN, consumes = JSON, produces = JSON)
  ResponseEntity<ApiResponse<TokenResponse>> refreshToken(
      @Valid @RequestBody RefreshTokenRequest request) {
    return noStore(HttpStatus.OK).body(ApiResponse.success(auth.refreshToken(request)));
  }

  @Operation(
      summary = "Start a password reset",
      description =
          "Returns the reset token for an existing account; an unknown account gets only the "
              + "message. Send the token to reset-password.",
      security = {})
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "A password reset has been started, if the account exists.",
        content =
            @Content(
                mediaType = JSON,
                examples =
                    @ExampleObject(name = "started", value = ApiExamples.PASSWORD_RESET_STARTED)))
  })
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "429",
      description =
          "Too many reset requests for that name (more than three in 15 minutes). The answer is "
              + "the same whether or not the account exists; `Retry-After` says how long to wait.",
      content = @Content(mediaType = JSON, schema = @Schema(ref = DocsConstants.API_ERROR_REF)))
  @PostMapping(value = ApiPaths.FORGOT_PASSWORD, consumes = JSON, produces = JSON)
  ResponseEntity<ApiResponse<ForgotPasswordResponse>> forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest request) {
    return noStore(HttpStatus.OK).body(ApiResponse.success(auth.forgotPassword(request)));
  }

  @Operation(
      summary = "Set a new password with a reset token",
      description =
          "A reset token works once and for a short time. Every session of the user is ended.",
      security = {})
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "The password was changed."),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "A field breaks a rule, or the reset token is unknown, expired or used.",
        content =
            @Content(
                mediaType = JSON,
                schema = @Schema(ref = DocsConstants.API_ERROR_REF),
                examples =
                    @ExampleObject(
                        name = "invalid-reset-token",
                        value = ApiExamples.INVALID_RESET_TOKEN)))
  })
  @PostMapping(value = ApiPaths.RESET_PASSWORD, consumes = JSON, produces = JSON)
  ResponseEntity<ApiResponse<LogoutResponse>> resetPassword(
      @Valid @RequestBody ResetPasswordRequest request) {
    return noStore(HttpStatus.OK).body(ApiResponse.success(auth.resetPassword(request)));
  }

  @Operation(
      summary = "Sign out by revoking a refresh token",
      description =
          "Always succeeds, also for an unknown or already revoked token. The access token stays "
              + "valid until it expires.",
      security = {})
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "200",
      description = "The refresh token is no longer valid.")
  @PostMapping(value = ApiPaths.LOGOUT, consumes = JSON, produces = JSON)
  ResponseEntity<ApiResponse<LogoutResponse>> logout(@Valid @RequestBody LogoutRequest request) {
    return noStore(HttpStatus.OK).body(ApiResponse.success(auth.logout(request)));
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(SecurityConstants.PRAGMA_HEADER, SecurityConstants.PRAGMA_NO_CACHE);
  }
}
